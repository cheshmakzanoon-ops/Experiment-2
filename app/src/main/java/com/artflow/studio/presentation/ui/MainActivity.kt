package com.artflow.studio.presentation.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
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
import com.artflow.studio.core.canvas.KeyboardShortcuts
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

    /**
     * Hardware-keyboard shortcuts are offered to the open editor before any view sees the key, so they work
     * wherever focus is, except inside a text field, where typing and the field's own Ctrl+Z come first.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && consumesAsShortcut(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun consumesAsShortcut(event: KeyEvent): Boolean {
        val handler = ShortcutHost.handler ?: return false
        if (currentFocus?.onCheckIsTextEditor() == true) return false
        val action = AndroidKeys.chord(event)?.let(KeyboardShortcuts::actionFor) ?: return false
        // A held key repeats only the actions made for it; the rest ignore the repeats but still own the key.
        return if (event.repeatCount > 0 && !action.repeats) true else handler(action)
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        if (ShortcutHost.handler != null) data.addAll(AndroidKeys.helperGroups())
    }
}
