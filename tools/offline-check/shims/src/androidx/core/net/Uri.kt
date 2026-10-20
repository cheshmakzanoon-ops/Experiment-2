// Harness shim of androidx.core.net (core-ktx Uri.kt).
@file:JvmName("ShimUriKt")
@file:Suppress("unused", "NOTHING_TO_INLINE")

package androidx.core.net

import android.net.Uri
import java.io.File

inline fun String.toUri(): Uri = Uri.parse(this)

inline fun File.toUri(): Uri = Uri.fromFile(this)

fun Uri.toFile(): File {
    require(scheme == "file") { "Uri lacks 'file' scheme: $this" }
    return File(requireNotNull(path) { "Uri path is null: $this" })
}
