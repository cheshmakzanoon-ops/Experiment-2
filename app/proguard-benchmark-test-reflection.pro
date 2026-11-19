# Benchmark instrumentation only. TraceReferences cannot discover string-based field lookup.
# SelectionGestureDeviceTest waits for the actual asynchronous selection job before asserting.
-keepclassmembers class com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView {
    kotlinx.coroutines.Job selectionJob;
}
# ArtworkWorkflowTest includes the registered Back callbacks in navigation failure diagnostics.
-keepclassmembers class androidx.activity.OnBackPressedDispatcher {
    kotlin.collections.ArrayDeque onBackPressedCallbacks;
}
