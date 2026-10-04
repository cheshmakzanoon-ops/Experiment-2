package com.artflow.studio.core.three

/** Writes a painted model as OBJ + MTL + texture, the set every 3D application can open. */
object ObjExport {
    const val OBJ = "model.obj"
    const val MTL = "model.mtl"
    const val TEXTURE = "texture.png"

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
    fun material(): String = "newmtl artwork\nKa 1 1 1\nKd 1 1 1\nKs 0 0 0\nmap_Kd $TEXTURE\n"
}
