package com.artflow.studio.core.three

/** Writes the painted atlas as OBJ + MTL. Scalar maps use the optional PBR MTL extensions. */
object ObjExport {
    const val OBJ = "model.obj"
    const val MTL = "model.mtl"
    const val TEXTURE = "texture.png"
    const val METALLIC = "metallic.png"
    const val ROUGHNESS = "roughness.png"

    /** [objText] with one material that uses the painted texture, replacing any it had. */
    fun withMaterial(objText: String): String {
        val body =
            objText
                .lineSequence()
                .filterNot { line -> line.trimStart().let { it.startsWith("mtllib") || it.startsWith("usemtl") } }
                .joinToString("\n")
        return "mtllib $MTL\nusemtl artwork\n$body\n"
    }

    /** The material: plain white lit colour multiplied by the painted texture. */
    fun material(channels: Set<MaterialChannel> = emptySet()): String =
        buildString {
            append("newmtl artwork\nKa 1 1 1\nKd 1 1 1\nKs 0.3 0.3 0.3\nmap_Kd $TEXTURE\n")
            append("Pm ${if (MaterialChannel.METALLIC in channels) 1f else MaterialChannel.METALLIC.defaultValue}\n")
            append("Pr ${if (MaterialChannel.ROUGHNESS in channels) 1f else MaterialChannel.ROUGHNESS.defaultValue}\n")
            if (MaterialChannel.METALLIC in channels) append("map_Pm $METALLIC\n")
            if (MaterialChannel.ROUGHNESS in channels) append("map_Pr $ROUGHNESS\n")
        }

    fun mapName(channel: MaterialChannel): String =
        when (channel) {
            MaterialChannel.COLOR -> TEXTURE
            MaterialChannel.METALLIC -> METALLIC
            MaterialChannel.ROUGHNESS -> ROUGHNESS
        }
}
