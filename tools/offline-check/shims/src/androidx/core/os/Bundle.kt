// Harness shim of androidx.core.os.bundleOf (core-ktx).
@file:JvmName("ShimBundleKt")
@file:Suppress("unused")

package androidx.core.os

import android.os.Bundle

fun bundleOf(vararg pairs: Pair<String, Any?>): Bundle = Bundle()
