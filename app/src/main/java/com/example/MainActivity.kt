package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.ui.screens.InternetGateScreen
import com.example.ui.screens.MapScreen
import com.example.ui.theme.IsfahanBusTheme
import com.example.ui.viewmodel.BusViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: BusViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            // The theme toggle is app-local, so it diverges from the system setting the
            // edge-to-edge API sampled at startup. Force icon contrast to follow the app.
            val barController = remember { WindowCompat.getInsetsController(window, window.decorView) }
            SideEffect {
                barController.isAppearanceLightStatusBars = !uiState.isDarkTheme
                barController.isAppearanceLightNavigationBars = !uiState.isDarkTheme
            }

            IsfahanBusTheme(darkTheme = uiState.isDarkTheme) {
                // Ensure complete app uses RTL Persian layout
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Crossfade(
                            targetState = uiState.isOffline,
                            label = "connectivity_gate_crossfade"
                        ) { isOffline ->
                            if (isOffline) {
                                // Blocking full-screen internet gate (§10)
                                InternetGateScreen(
                                    isChecking = uiState.isCheckingInternet,
                                    onRetry = { viewModel.retryConnection() }
                                )
                            } else {
                                // Main Map experience
                                MapScreen(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setAppResumed(true)
    }

    override fun onPause() {
        super.onPause()
        viewModel.setAppResumed(false)
    }
}
