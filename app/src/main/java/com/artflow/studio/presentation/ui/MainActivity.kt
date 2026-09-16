package com.artflow.studio.presentation.ui

import android.os.Bundle
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
}
