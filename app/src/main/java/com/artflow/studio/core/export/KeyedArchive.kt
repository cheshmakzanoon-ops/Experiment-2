package com.artflow.studio.core.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

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

    /**
     * Writes [value] as a binary property list that [read] (and Apple's readers) accept: null,
     * Boolean, Int or Long, Double, String, ByteArray, List, Map with String keys, and [Uid]
     * references. Equal scalars share one object, as Apple's writer does.
     */
    fun write(value: Any?): ByteArray {
        val writer = Writer()
        val top = writer.add(value)
        return writer.finish(top)
    }

    /** One object of the table: a scalar already encoded, or a container whose references are resolved at the end. */
    private sealed interface Entry

    private class Scalar(
        val bytes: ByteArray,
    ) : Entry

    private class Container(
        val marker: Int,
        val count: Int,
        val refs: IntArray,
    ) : Entry

    private class Writer {
        private val entries = ArrayList<Entry>()
        private val shared = HashMap<Any, Int>()

        /** Adds [value] and everything in it; returns its object index. */
        fun add(value: Any?): Int {
            val key: Any? =
                when (value) {
                    null, is Boolean, is Long, is Double, is String, is Uid -> value ?: NULL
                    is Int -> value.toLong()
                    else -> null
                }
            if (key != null) shared[key]?.let { return it }
            val index =
                when (value) {
                    null -> scalar(byteArrayOf(0x00))
                    is Boolean -> scalar(byteArrayOf(if (value) 0x09 else 0x08))
                    is Int, is Long -> scalar(integer(if (value is Int) value.toLong() else value as Long))
                    is Double -> scalar(byteArrayOf(0x23) + ByteBuffer.allocate(8).putDouble(value).array())
                    is String -> scalar(string(value))
                    is ByteArray -> scalar(sized(0x40, value.size) + value)
                    is Uid -> scalar(uid(value.index))
                    is List<*> -> container(0xA0, value.size, value.map { add(it) })
                    is Map<*, *> ->
                        container(
                            0xD0,
                            value.size,
                            value.keys.map { add(it) } + value.values.map { add(it) },
                        )
                    else -> throw IllegalArgumentException("A ${value::class.simpleName} cannot be written to a property list")
                }
            if (key != null) shared[key] = index
            return index
        }

        private fun scalar(bytes: ByteArray): Int {
            entries += Scalar(bytes)
            return entries.lastIndex
        }

        private fun container(
            marker: Int,
            count: Int,
            refs: List<Int>,
        ): Int {
            entries += Container(marker, count, refs.toIntArray())
            return entries.lastIndex
        }

        /** The table with its offsets and trailer, rooted at [top]. */
        fun finish(top: Int): ByteArray {
            val refSize = sizeOf(entries.size.toLong() - 1)
            val body = ByteArrayOutputStream()
            body.write(MAGIC)
            val offsets = IntArray(entries.size)
            for (index in entries.indices) {
                offsets[index] = body.size()
                when (val entry = entries[index]) {
                    is Scalar -> body.write(entry.bytes)
                    is Container -> {
                        if (entry.count < 15) {
                            body.write(entry.marker or entry.count)
                        } else {
                            body.write(entry.marker or 0xF)
                            body.write(integer(entry.count.toLong()))
                        }
                        entry.refs.forEach { body.write(number(it.toLong(), refSize)) }
                    }
                }
            }
            val tableOffset = body.size()
            val offsetSize = sizeOf(tableOffset.toLong())
            offsets.forEach { body.write(number(it.toLong(), offsetSize)) }
            body.write(ByteArray(TRAILER_UNUSED))
            body.write(offsetSize)
            body.write(refSize)
            body.write(number(entries.size.toLong(), 8))
            body.write(number(top.toLong(), 8))
            body.write(number(tableOffset.toLong(), 8))
            return body.toByteArray()
        }

        private fun integer(value: Long): ByteArray =
            when {
                value in 0..0xFF -> byteArrayOf(0x10) + number(value, 1)
                value in 0..0xFFFF -> byteArrayOf(0x11) + number(value, 2)
                value in 0..0xFFFFFFFFL -> byteArrayOf(0x12) + number(value, 4)
                else -> byteArrayOf(0x13) + number(value, 8)
            }

        private fun string(text: String): ByteArray {
            val ascii = text.all { it.code < 0x80 }
            return if (ascii) {
                sized(0x50, text.length) + text.toByteArray(Charsets.ISO_8859_1)
            } else {
                val utf16 = text.toByteArray(Charsets.UTF_16BE)
                sized(0x60, utf16.size / 2) + utf16
            }
        }

        private fun uid(index: Int): ByteArray {
            val size = sizeOf(index.toLong())
            return byteArrayOf((0x80 or (size - 1)).toByte()) + number(index.toLong(), size)
        }

        /** A marker with its length: inline when short, else as a separate integer. */
        private fun sized(
            marker: Int,
            length: Int,
        ): ByteArray =
            if (length < 15) {
                byteArrayOf((marker or length).toByte())
            } else {
                byteArrayOf((marker or 0xF).toByte()) + integer(length.toLong())
            }

        private object NULL
    }

    /** The fewest bytes, from 1 to 8, that hold [value] unsigned. */
    private fun sizeOf(value: Long): Int {
        var size = 1
        while (size < 8 && value ushr (8 * size) != 0L) size++
        return size
    }

    /** [value] as [size] big-endian bytes (the low bytes when it is wider). */
    private fun number(
        value: Long,
        size: Int,
    ): ByteArray = ByteArray(size) { k -> (value ushr (8 * (size - 1 - k))).toByte() }

    private const val TRAILER_UNUSED = 6

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

/**
 * Builds an NSKeyedArchiver archive that [KeyedArchive] reads: objects go in one flat list and refer to each
 * other by [BinaryPlist.Uid], with the list's first entry the null object, as Apple's archiver writes them.
 */
class KeyedArchiveBuilder {
    private val objects = arrayListOf<Any?>("\$null")
    private val classes = HashMap<String, BinaryPlist.Uid>()

    /** Adds [value] as an object and returns the reference to it. */
    fun add(value: Any?): BinaryPlist.Uid {
        objects += value
        return BinaryPlist.Uid(objects.lastIndex)
    }

    /** The class description for [name], added once. */
    fun classOf(name: String): BinaryPlist.Uid =
        classes.getOrPut(name) { add(mapOf("\$classname" to name, "\$classes" to listOf(name, "NSObject"))) }

    /** An object of class [className] whose [fields] may hold references from this builder. */
    fun instance(
        className: String,
        fields: Map<String, Any?>,
    ): BinaryPlist.Uid = add(mapOf("\$class" to classOf(className)) + fields)

    /** An NSMutableArray of [items]. */
    fun array(items: List<BinaryPlist.Uid>): BinaryPlist.Uid = add(mapOf("\$class" to classOf("NSMutableArray"), "NS.objects" to items))

    /** The archive as a binary property list, with [root] as its top object. */
    fun encode(root: Map<String, Any?>): ByteArray {
        val rootRef = add(root)
        return BinaryPlist.write(
            mapOf(
                "\$archiver" to "NSKeyedArchiver",
                "\$version" to 100_000L,
                "\$top" to mapOf("root" to rootRef),
                "\$objects" to objects.toList(),
            ),
        )
    }
}
