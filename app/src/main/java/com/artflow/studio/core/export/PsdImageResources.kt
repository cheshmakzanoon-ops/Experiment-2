package com.artflow.studio.core.export

/** Metadata only: every name, payload and even-byte padding stays inside the resource section. */
internal data class PsdImageResources(
    val dpi: Int = 72,
    val transparencyChannel: Int? = null,
    val hasEmbeddedIccProfile: Boolean = false,
) {
    companion object {
        fun read(
            reader: PsdCodec.PsdReader,
            sectionEnd: Int,
            channelCount: Int,
            colorChannels: Int,
        ): PsdImageResources {
            var dpi = 72
            var transparency: Int? = null
            var hasProfile = false
            val seen = mutableSetOf<Int>()
            while (reader.position < sectionEnd) {
                require(sectionEnd - reader.position >= 12) { "Truncated PSD image resource" }
                val signature = reader.readAscii(4)
                require(signature == "8BIM" || signature == "8B64") { "Invalid PSD image resource signature" }
                val id = reader.readShort()
                val nameLength = reader.readByte()
                val nameBytes = nameLength + ((nameLength + 1) and 1)
                require(nameBytes + 4 <= sectionEnd - reader.position) { "Truncated PSD resource name" }
                reader.skip(nameBytes)
                val length = reader.readInt()
                require(length >= 0 && length.toLong() + (length and 1) <= sectionEnd - reader.position) {
                    "PSD resource payload exceeds its section"
                }
                val paddedEnd = reader.position + length + (length and 1)
                if (id == 1005 || id == 1039 || id == 1053) {
                    require(seen.add(id)) { "Duplicate PSD color or channel metadata" }
                }
                when (id) {
                    1005 -> {
                        require(length == 16) { "Invalid PSD resolution resource" }
                        val horizontal = (reader.readInt().toLong() and 0xFFFFFFFFL) / 65536.0
                        val unit = reader.readShort()
                        val perInch = if (unit == 2) horizontal * 2.54 else horizontal
                        if (perInch >= 1.0) dpi = perInch.toInt()
                    }

                    1039 -> hasProfile = length > 0
                    1053 -> transparency = readAlphaIdentifiers(reader, length, channelCount, colorChannels)
                }
                reader.position = paddedEnd
            }
            return PsdImageResources(dpi, transparency, hasProfile)
        }

        private fun readAlphaIdentifiers(
            reader: PsdCodec.PsdReader,
            length: Int,
            channelCount: Int,
            colorChannels: Int,
        ): Int? {
            // The enclosing resource size is the length; its payload is a sequence of uint32 IDs.
            // Photoshop/psd-tools use ID zero for merged transparency. IDs describe trailing
            // channels, and must never point into the base-color planes. Stale metadata may list
            // zero even without a corresponding transparency plane, so do not infer one then.
            require(length % 4 == 0 && length / 4 <= 56) { "Invalid PSD alpha identifier resource" }
            val count = length / 4
            var zeroIndex: Int? = null
            repeat(count) { index ->
                if (reader.readInt() == 0) {
                    require(zeroIndex == null) { "Ambiguous PSD transparency identifiers" }
                    zeroIndex = index
                }
            }
            val channel = zeroIndex?.let { channelCount - count + it } ?: return null
            return channel.takeIf { count <= channelCount - colorChannels && it in colorChannels until channelCount }
        }
    }
}
