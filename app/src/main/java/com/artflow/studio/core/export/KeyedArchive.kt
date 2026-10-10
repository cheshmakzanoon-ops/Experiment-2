package com.artflow.studio.core.export

/**
 * Apple binary property lists ("bplist00") and the keyed archives stored in them (NSKeyedArchiver,
 * as Procreate saves its document description). Plist values come back as Kotlin values: null,
 * Boolean, Long, Double, String, ByteArray, List, Map with String keys, and [Uid] references.
 */
object BinaryPlist {
    /** A reference into a keyed archive's object table. */
    data class Uid(
        val index: Int,
    )

    private val MAGIC = "bplist00".toByteArray()

    fun isBinaryPlist(bytes: ByteArray): Boolean = bytes.size > MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun read(bytes: ByteArray): Any? {
        require(bytes.size >= MAGIC.size + TRAILER && isBinaryPlist(bytes)) { "This is not a binary property list" }
        val trailer = bytes.size - TRAILER
        val offsetSize = bytes[trailer + 6].toInt() and 0xFF
        val refSize = bytes[trailer + 7].toInt() and 0xFF
        val count = number(bytes, trailer + 8, 8)
        val top = number(bytes, trailer + 16, 8)
        val table = number(bytes, trailer + 24, 8)
        require(offsetSize in 1..8 && refSize in 1..8 && count in 1..MAX_OBJECTS && top in 0 until count) { DAMAGED }
        require(table >= MAGIC.size && table + count * offsetSize <= trailer) { DAMAGED }
        val offsets = IntArray(count.toInt()) { index -> number(bytes, (table + index * offsetSize).toInt(), offsetSize).toInt() }
        require(offsets.all { it in MAGIC.size until trailer }) { DAMAGED }
        return Reader(bytes, offsets, refSize).read(top.toInt(), 0)
    }

    private class Reader(
        val bytes: ByteArray,
        val offsets: IntArray,
        val refSize: Int,
    ) {
        private var reads = 0

        fun read(
            index: Int,
            depth: Int,
        ): Any? {
            require(index in offsets.indices && depth < MAX_DEPTH) { DAMAGED }
            // Shared references make the graph a DAG whose expansion can be exponential in its depth.
            require(++reads <= MAX_READS) { DAMAGED }
            var at = offsets[index]
            val marker = bytes[at++].toInt() and 0xFF
            val kind = marker ushr 4
            val info = marker and 0xF
            return when (kind) {
                0x0 ->
                    if (info == 0x9) {
                        true
                    } else if (info == 0x8) {
                        false
                    } else {
                        null
                    }
                0x1 -> number(bytes, at, 1 shl info)
                0x2 -> real(at, 1 shl info)
                0x3 -> real(at, 8)
                0x8 -> Uid(number(bytes, at, info + 1).toInt())
                else -> container(kind, info, at, depth)
            }
        }

        private fun container(
            kind: Int,
            info: Int,
            start: Int,
            depth: Int,
        ): Any? {
            var at = start
            var length = info.toLong()
            if (info == 0xF) {
                val size = 1 shl (bytes[at].toInt() and 0xF)
                length = number(bytes, at + 1, size)
                at += 1 + size
            }
            require(length in 0..bytes.size) { DAMAGED }
            val n = length.toInt()
            return when (kind) {
                0x4 -> slice(at, n)
                0x5 -> String(slice(at, n), Charsets.ISO_8859_1)
                0x6 -> String(slice(at, n * 2), Charsets.UTF_16BE)
                0xA, 0xC -> List(n) { read(ref(at + it * refSize), depth + 1) }
                0xD ->
                    (0 until n).associate { i ->
                        read(ref(at + i * refSize), depth + 1).toString() to read(ref(at + (n + i) * refSize), depth + 1)
                    }
                else -> null
            }
        }

        private fun ref(at: Int): Int = number(bytes, at, refSize).toInt()

        private fun slice(
            at: Int,
            length: Int,
        ): ByteArray {
            require(at >= 0 && length <= bytes.size - at) { DAMAGED }
            return bytes.copyOfRange(at, at + length)
        }

        private fun real(
            at: Int,
            size: Int,
        ): Double {
            val bits = number(bytes, at, size)
            return if (size == 4) {
                java.lang.Float
                    .intBitsToFloat(bits.toInt())
                    .toDouble()
            } else {
                java.lang.Double.longBitsToDouble(bits)
            }
        }
    }

    /** A big-endian unsigned number of [size] bytes (the low 8 bytes of a wider one). */
    private fun number(
        bytes: ByteArray,
        at: Int,
        size: Int,
    ): Long {
        require(at >= 0 && size in 1..MAX_NUMBER && size <= bytes.size - at) { DAMAGED }
        var value = 0L
        for (k in maxOf(0, size - 8) until size) value = (value shl 8) or (bytes[at + k].toLong() and 0xFF)
        return value
    }

    private const val TRAILER = 32
    private const val MAX_OBJECTS = 4_000_000L
    private const val MAX_DEPTH = 64
    private const val MAX_READS = 200_000
    private const val MAX_NUMBER = 16
    private const val DAMAGED = "The property list is damaged"
}

/**
 * An NSKeyedArchiver archive: the object table with its root, and helpers that follow [BinaryPlist.Uid]
 * references to strings, arrays, data and class names.
 */
class KeyedArchive(
    plist: Any?,
) {
    private val objects: List<Any?>
    val root: Map<String, Any?>?

    init {
        val top = requireNotNull(plist as? Map<*, *>) { "This is not a keyed archive" }
        objects = (top["\$objects"] as? List<*>).orEmpty()
        root = map((top["\$top"] as? Map<*, *>)?.get("root"))
    }

    /** The object a [BinaryPlist.Uid] points to; other values are returned as they are. */
    fun resolve(value: Any?): Any? = if (value is BinaryPlist.Uid) objects.getOrNull(value.index) else value

    @Suppress("UNCHECKED_CAST")
    fun map(value: Any?): Map<String, Any?>? = resolve(value) as? Map<String, Any?>

    fun string(value: Any?): String? =
        when (val resolved = resolve(value)) {
            is String -> resolved.takeIf { it != NULL }
            is Map<*, *> -> resolve(resolved["NS.string"]) as? String
            else -> null
        }

    /** An NSArray's (or NSSet's) members, resolved. */
    fun array(value: Any?): List<Any?> = (map(value)?.get("NS.objects") as? List<*>).orEmpty().map(::resolve)

    fun data(value: Any?): ByteArray? =
        when (val resolved = resolve(value)) {
            is ByteArray -> resolved
            is Map<*, *> -> resolve(resolved["NS.data"]) as? ByteArray
            else -> null
        }

    fun className(value: Any?): String? = map(map(value)?.get("\$class"))?.get("\$classname") as? String

    private companion object {
        const val NULL = "\$null"
    }
}
