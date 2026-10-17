package com.artflow.studio.core.export

/** What a file handed to the app by another app, or picked in a file chooser, can become. */
enum class FileKind {
    /** A photo or picture: becomes a new artwork. */
    IMAGE,
    PSD,
    PROCREATE_DOCUMENT,
    ARTFLOW_ARTWORK,

    /** Procreate `.brush` and `.brushset`, Photoshop `.abr` and ArtFlow `.artbrush`: join the brush library. */
    BRUSH_FILE,

    /** Procreate `.swatches`, Adobe `.ase` and GIMP `.gpl`: become a palette. */
    PALETTE_FILE,
    MODEL,

    /** A ZIP with no telling name: its contents decide which of the kinds above it is. */
    ZIP,
    UNKNOWN,
}

/** Works out a [FileKind] from the name, the type the sender claimed and the first bytes; the name wins, then the bytes, then the type. */
object FileKinds {
    private val byExtension: Map<String, FileKind> =
        buildMap {
            put("procreate", FileKind.PROCREATE_DOCUMENT)
            listOf("psd", "psb").forEach { put(it, FileKind.PSD) }
            put("artflow", FileKind.ARTFLOW_ARTWORK)
            listOf("brush", "brushset", "abr", "artbrush").forEach { put(it, FileKind.BRUSH_FILE) }
            listOf("swatches", "ase", "gpl").forEach { put(it, FileKind.PALETTE_FILE) }
            listOf("obj", "gltf", "glb", "usdz", "usd", "usda", "usdc").forEach { put(it, FileKind.MODEL) }
            listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif").forEach { put(it, FileKind.IMAGE) }
        }

    fun classify(
        name: String?,
        mimeType: String?,
        head: ByteArray,
    ): FileKind {
        name?.substringAfterLast('.', "")?.lowercase()?.let { byExtension[it] }?.let { return it }
        fromBytes(head)?.let { return it }
        val type = mimeType?.lowercase().orEmpty()
        return when {
            type == "image/vnd.adobe.photoshop" || type == "application/x-photoshop" || type == "image/x-photoshop" -> FileKind.PSD
            type.startsWith("image/") -> FileKind.IMAGE
            type.startsWith("model/") -> FileKind.MODEL
            else -> FileKind.UNKNOWN
        }
    }

    private fun fromBytes(head: ByteArray): FileKind? =
        when {
            head.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> FileKind.IMAGE
            head.startsWith(0xFF, 0xD8, 0xFF) -> FileKind.IMAGE
            head.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) -> FileKind.IMAGE
            head.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && head.size >= 12 && head.copyOfRange(8, 12).contentEquals(WEBP) ->
                FileKind.IMAGE
            head.startsWith('8'.code, 'B'.code, 'P'.code, 'S'.code) -> FileKind.PSD
            head.startsWith('A'.code, 'S'.code, 'E'.code, 'F'.code) -> FileKind.PALETTE_FILE
            head.startsWith('G'.code, 'I'.code, 'M'.code, 'P'.code, ' '.code, 'P'.code) -> FileKind.PALETTE_FILE
            head.startsWith('g'.code, 'l'.code, 'T'.code, 'F'.code) -> FileKind.MODEL
            head.startsWith('P'.code, 'K'.code, 3, 4) -> FileKind.ZIP
            else -> null
        }

    private val WEBP = byteArrayOf('W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }
}
