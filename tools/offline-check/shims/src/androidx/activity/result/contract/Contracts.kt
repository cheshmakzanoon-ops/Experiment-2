// Harness shim of androidx.activity.result.contract (ActivityResultContract + ActivityResultContracts built-ins).
@file:JvmName("ShimContractsKt")
@file:Suppress("unused")

package androidx.activity.result.contract

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest

abstract class ActivityResultContract<I, O> {
    abstract fun createIntent(
        context: Context,
        input: I,
    ): Intent

    abstract fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): O

    open fun getSynchronousResult(
        context: Context,
        input: I,
    ): SynchronousResult<O>? = null

    class SynchronousResult<T>(
        val value: T,
    )
}

class ActivityResultContracts private constructor() {
    class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(
            context: Context,
            input: Intent,
        ): Intent = input

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): ActivityResult = ActivityResult(resultCode, intent)

        companion object {
            const val ACTION_INTENT_SENDER_REQUEST: String = "androidx.activity.result.contract.action.INTENT_SENDER_REQUEST"
            const val EXTRA_ACTIVITY_OPTIONS_BUNDLE: String = "androidx.activity.result.contract.extra.ACTIVITY_OPTIONS_BUNDLE"
        }
    }

    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun createIntent(
            context: Context,
            input: Array<String>,
        ): Intent = Intent()

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Map<String, Boolean> = emptyMap()

        companion object {
            const val ACTION_REQUEST_PERMISSIONS: String = "androidx.activity.result.contract.action.REQUEST_PERMISSIONS"
            const val EXTRA_PERMISSIONS: String = "androidx.activity.result.contract.extra.PERMISSIONS"
            const val EXTRA_PERMISSION_GRANT_RESULTS: String = "androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS"
        }
    }

    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun createIntent(
            context: Context,
            input: String,
        ): Intent = Intent()

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Boolean = false
    }

    class TakePicturePreview : ActivityResultContract<Void?, android.graphics.Bitmap?>() {
        override fun createIntent(
            context: Context,
            input: Void?,
        ): Intent = Intent()

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): android.graphics.Bitmap? = null
    }

    open class TakePicture : ActivityResultContract<Uri, Boolean>() {
        override fun createIntent(
            context: Context,
            input: Uri,
        ): Intent = Intent()

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Boolean = resultCode == android.app.Activity.RESULT_OK
    }

    open class CaptureVideo : ActivityResultContract<Uri, Boolean>() {
        override fun createIntent(
            context: Context,
            input: Uri,
        ): Intent = Intent()

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Boolean = resultCode == android.app.Activity.RESULT_OK
    }

    open class CreateDocument(
        private val mimeType: String,
    ) : ActivityResultContract<String, Uri?>() {
        @Deprecated("Using a wildcard mime type is discouraged")
        constructor() : this("*/*")

        override fun createIntent(
            context: Context,
            input: String,
        ): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mimeType).putExtra(Intent.EXTRA_TITLE, input)

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Uri? = intent?.data
    }

    open class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(
            context: Context,
            input: Uri?,
        ): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Uri? = intent?.data
    }

    open class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun createIntent(
            context: Context,
            input: Array<String>,
        ): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).putExtra(Intent.EXTRA_MIME_TYPES, input).setType("*/*")

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Uri? = intent?.data
    }

    open class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun createIntent(
            context: Context,
            input: Array<String>,
        ): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): List<Uri> = emptyList()
    }

    open class GetContent : ActivityResultContract<String, Uri?>() {
        override fun createIntent(
            context: Context,
            input: String,
        ): Intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input)

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Uri? = intent?.data
    }

    open class GetMultipleContents : ActivityResultContract<String, List<Uri>>() {
        override fun createIntent(
            context: Context,
            input: String,
        ): Intent = Intent(Intent.ACTION_GET_CONTENT)

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): List<Uri> = emptyList()
    }

    open class PickContact : ActivityResultContract<Void?, Uri?>() {
        override fun createIntent(
            context: Context,
            input: Void?,
        ): Intent = Intent(Intent.ACTION_PICK)

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Uri? = intent?.data
    }

    open class PickVisualMedia : ActivityResultContract<PickVisualMediaRequest, Uri?>() {
        override fun createIntent(
            context: Context,
            input: PickVisualMediaRequest,
        ): Intent = Intent()

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Uri? = intent?.data

        sealed interface VisualMediaType

        object ImageOnly : VisualMediaType

        object VideoOnly : VisualMediaType

        object ImageAndVideo : VisualMediaType

        class SingleMimeType(
            val mimeType: String,
        ) : VisualMediaType

        companion object {
            @JvmStatic
            fun isPhotoPickerAvailable(): Boolean = true
        }
    }
}
