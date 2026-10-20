// Harness shim: androidx.compose.material3 dynamic colour schemes (androidMain only, API 31+).
@file:JvmName("ShimDynamicColorKt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.material3

import android.content.Context

fun dynamicDarkColorScheme(context: Context): ColorScheme = darkColorScheme()

fun dynamicLightColorScheme(context: Context): ColorScheme = lightColorScheme()
