// Harness shim: androidx.compose.ui.platform Android composition locals (androidMain, absent from the desktop jars).
@file:JvmName("ShimAndroidCompositionLocalsKt")
@file:Suppress("unused")

package androidx.compose.ui.platform

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext: ProvidableCompositionLocal<Context> =
    staticCompositionLocalOf { error("CompositionLocal LocalContext not present") }

val LocalConfiguration: ProvidableCompositionLocal<Configuration> =
    compositionLocalOf { error("CompositionLocal LocalConfiguration not present") }

val LocalView: ProvidableCompositionLocal<View> =
    staticCompositionLocalOf { error("CompositionLocal LocalView not present") }
