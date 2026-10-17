// Harness shim of androidx.core:core / core-ktx 1.17.x -- ContextCompat, FileProvider, WindowCompat, MathUtils,
// graphics KTX.  Declarations only (bodies throw or return neutral values).
@file:JvmName("ShimCoreKt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.core.content

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.util.concurrent.Executor

object ContextCompat {
    @JvmStatic
    fun checkSelfPermission(
        context: Context,
        permission: String,
    ): Int = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun getColor(
        context: Context,
        id: Int,
    ): Int = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun getColorStateList(
        context: Context,
        id: Int,
    ): ColorStateList = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun getDrawable(
        context: Context,
        id: Int,
    ): Drawable? = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun <T : Any> getSystemService(
        context: Context,
        serviceClass: Class<T>,
    ): T? = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun getMainExecutor(context: Context): Executor = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun startActivity(
        context: Context,
        intent: Intent,
        options: Bundle?,
    ): Unit = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun startForegroundService(
        context: Context,
        intent: Intent,
    ): Unit = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun getExternalFilesDirs(
        context: Context,
        type: String?,
    ): Array<File?> = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun getCodeCacheDir(context: Context): File = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun getDataDir(context: Context): File? = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun getNoBackupFilesDir(context: Context): File = throw UnsupportedOperationException("harness stub")
}

open class FileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException("No external inserts")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("No external updates")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        @JvmStatic
        fun getUriForFile(
            context: Context,
            authority: String,
            file: File,
        ): Uri = throw UnsupportedOperationException("harness stub")

        @JvmStatic
        fun getUriForFile(
            context: Context,
            authority: String,
            file: File,
            displayName: String,
        ): Uri = throw UnsupportedOperationException("harness stub")
    }
}

object IntentCompat {
    @JvmStatic
    fun <T> getParcelableExtra(
        `in`: Intent,
        name: String?,
        clazz: Class<T>,
    ): T? = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun <T> getParcelableArrayExtra(
        `in`: Intent,
        name: String?,
        clazz: Class<out T>,
    ): Array<T>? = throw UnsupportedOperationException("harness stub")

    @JvmStatic
    fun <T> getParcelableArrayListExtra(
        `in`: Intent,
        name: String?,
        clazz: Class<out T>,
    ): ArrayList<T>? = throw UnsupportedOperationException("harness stub")
}

inline fun android.content.SharedPreferences.edit(
    commit: Boolean = false,
    action: android.content.SharedPreferences.Editor.() -> Unit,
) {
    val editor = edit()
    action(editor)
    if (commit) editor.commit() else editor.apply()
}

inline fun <reified T : Any> Context.getSystemService(): T? = ContextCompat.getSystemService(this, T::class.java)
