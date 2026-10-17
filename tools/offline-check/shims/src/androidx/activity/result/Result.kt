// Harness shim of androidx.activity.result (ActivityResult, ActivityResultLauncher, ActivityResultRegistry, ...).
@file:JvmName("ShimActivityResultKt")
@file:Suppress("unused")

package androidx.activity.result

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.LifecycleOwner

class ActivityResult(
    val resultCode: Int,
    val data: Intent?,
) {
    operator fun component1(): Int = resultCode

    operator fun component2(): Intent? = data

    companion object {
        @JvmStatic
        fun resultCodeToString(resultCode: Int): String =
            when (resultCode) {
                android.app.Activity.RESULT_OK -> "RESULT_OK"
                android.app.Activity.RESULT_CANCELED -> "RESULT_CANCELED"
                else -> resultCode.toString()
            }
    }
}

fun interface ActivityResultCallback<O> {
    fun onActivityResult(result: O)
}

abstract class ActivityResultLauncher<I> {
    open fun launch(input: I) {
        launch(input, null)
    }

    abstract fun launch(
        input: I,
        options: ActivityOptionsCompat?,
    )

    abstract fun unregister()

    abstract val contract: ActivityResultContract<I, *>
}

interface ActivityResultCaller {
    fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>,
    ): ActivityResultLauncher<I>

    fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        registry: ActivityResultRegistry,
        callback: ActivityResultCallback<O>,
    ): ActivityResultLauncher<I>
}

abstract class ActivityResultRegistry {
    fun <I, O> register(
        key: String,
        lifecycleOwner: LifecycleOwner,
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>,
    ): ActivityResultLauncher<I> = register(key, contract, callback)

    fun <I, O> register(
        key: String,
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>,
    ): ActivityResultLauncher<I> =
        object : ActivityResultLauncher<I>() {
            override fun launch(
                input: I,
                options: ActivityOptionsCompat?,
            ) {}

            override fun unregister() {}

            override val contract: ActivityResultContract<I, *> get() = contract
        }

    abstract fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    )
}

interface ActivityResultRegistryOwner {
    val activityResultRegistry: ActivityResultRegistry
}

class PickVisualMediaRequest internal constructor() {
    var mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo
        internal set

    class Builder {
        private var type: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo

        fun setMediaType(mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType): Builder = apply { type = mediaType }

        fun build(): PickVisualMediaRequest = PickVisualMediaRequest().also { it.mediaType = type }
    }
}

fun PickVisualMediaRequest(
    mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo,
): PickVisualMediaRequest = PickVisualMediaRequest.Builder().setMediaType(mediaType).build()
