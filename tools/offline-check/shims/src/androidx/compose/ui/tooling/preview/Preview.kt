// Harness shim of androidx.compose.ui:ui-tooling-preview 1.7.x (the desktop jar ships a different, desktop-only @Preview).
@file:Suppress("unused")

package androidx.compose.ui.tooling.preview

import kotlin.reflect.KClass

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Repeatable
annotation class Preview(
    val name: String = "",
    val group: String = "",
    val apiLevel: Int = -1,
    val widthDp: Int = -1,
    val heightDp: Int = -1,
    val locale: String = "",
    val fontScale: Float = 1f,
    val showSystemUi: Boolean = false,
    val showBackground: Boolean = false,
    val backgroundColor: Long = 0,
    val uiMode: Int = 0,
    val device: String = Devices.DEFAULT,
    val wallpaper: Int = Wallpapers.NONE,
)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class PreviewParameter(
    val provider: KClass<out PreviewParameterProvider<*>>,
    val limit: Int = Int.MAX_VALUE,
)

interface PreviewParameterProvider<T> {
    val values: Sequence<T>

    val count: Int get() = values.count()
}

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class PreviewFontScale

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
annotation class PreviewLightDark

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
annotation class PreviewScreenSizes

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
annotation class PreviewDynamicColors

object Wallpapers {
    const val NONE = -1
    const val RED_DOMINATED_EXAMPLE = 0
    const val GREEN_DOMINATED_EXAMPLE = 1
    const val BLUE_DOMINATED_EXAMPLE = 2
    const val YELLOW_DOMINATED_EXAMPLE = 3
}

object Devices {
    const val DEFAULT = ""
    const val NEXUS_7 = "id:Nexus 7"
    const val NEXUS_7_2013 = "id:Nexus 7 2013"
    const val NEXUS_5 = "id:Nexus 5"
    const val NEXUS_6 = "id:Nexus 6"
    const val NEXUS_9 = "id:Nexus 9"
    const val NEXUS_10 = "name:Nexus 10"
    const val NEXUS_5X = "id:Nexus 5X"
    const val NEXUS_6P = "id:Nexus 6P"
    const val PIXEL_C = "id:pixel_c"
    const val PIXEL = "id:pixel"
    const val PIXEL_XL = "id:pixel_xl"
    const val PIXEL_2 = "id:pixel_2"
    const val PIXEL_2_XL = "id:pixel_2_xl"
    const val PIXEL_3 = "id:pixel_3"
    const val PIXEL_3_XL = "id:pixel_3_xl"
    const val PIXEL_3A = "id:pixel_3a"
    const val PIXEL_3A_XL = "id:pixel_3a_xl"
    const val PIXEL_4 = "id:pixel_4"
    const val PIXEL_4_XL = "id:pixel_4_xl"
    const val PIXEL_5 = "id:pixel_5"
    const val PIXEL_6 = "id:pixel_6"
    const val PIXEL_6_PRO = "id:pixel_6_pro"
    const val PIXEL_7 = "id:pixel_7"
    const val PIXEL_7_PRO = "id:pixel_7_pro"
    const val PIXEL_FOLD = "id:pixel_fold"
    const val PIXEL_TABLET = "id:pixel_tablet"
    const val AUTOMOTIVE_1024p = "id:automotive_1024p_landscape"
    const val WEAR_OS_LARGE_ROUND = "id:wearos_large_round"
    const val WEAR_OS_SMALL_ROUND = "id:wearos_small_round"
    const val WEAR_OS_SQUARE = "id:wearos_square"
    const val WEAR_OS_RECT = "id:wearos_rect"
    const val PHONE = "spec:shape=Normal,width=360,height=640,unit=dp,dpi=480"
    const val FOLDABLE = "spec:shape=Normal,width=673,height=841,unit=dp,dpi=480"
    const val TABLET = "spec:shape=Normal,width=1280,height=800,unit=dp,dpi=240"
    const val DESKTOP = "spec:shape=Normal,width=1920,height=1080,unit=dp,dpi=160"
}
