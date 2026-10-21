package com.artflow.studio.presentation.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.artflow.studio.R
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity.
 *
 * The theme is driven by the stored preferences, so the first frame already matches the user's
 * theme, accent and accessibility settings instead of flashing the defaults.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest starts the cold-start window on Theme.ArtFlow.Starting; swap to the real
        // window theme before the first frame so insets, system bars and dialogs are correct.
        setTheme(R.style.Theme_ArtFlow)
        super.onCreate(savedInstanceState)
        // Window.Callback is the public interception point; AndroidX's inherited dispatchKeyEvent
        // implementation is library-restricted. Delegate every event the editor does not consume.
        window.callback =
            EditorKeyboardCallback(
                delegate = window.callback,
                textEditorFocused = { currentFocus?.onCheckIsTextEditor() == true },
                shortcutHandler = { ShortcutHost.handler },
            )
        // A recreated activity must not import the file that started it a second time.
        if (savedInstanceState == null) acceptFiles(intent)
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsState()
            ArtFlowTheme(settings = settings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ArtFlowApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptFiles(intent)
    }

    /** Queues files opened from other apps; while an artwork is open they wait for the gallery, and the user is told. */
    private fun acceptFiles(intent: Intent?) {
        val received = IncomingFiles.offer(intent)
        // The editor registers as the shortcut host, so a registered host means an artwork is open.
        if (received > 0 && ShortcutHost.handler != null) {
            Toast.makeText(this, R.string.incoming_files_wait_for_gallery, Toast.LENGTH_LONG).show()
        }
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        if (ShortcutHost.handler != null) data.addAll(AndroidKeys.helperGroups())
    }
}
