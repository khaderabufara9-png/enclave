package com.example

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.EnclaveViewModel
import com.example.ui.navigation.EnclaveRootApp

class MainActivity : FragmentActivity() {

    private val viewModel: EnclaveViewModel by viewModels()
    private var isInBackgroundCurtain by mutableStateOf(false)
    private var pendingDeepLinkUri by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingDeepLinkUri = intent?.data?.toString()

        setContent {
            val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            LaunchedEffect(settings.screenshotProtection) {
                if (settings.screenshotProtection) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val layoutDirection = if (settings.languageCode == "ar") {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                EnclaveRootApp(
                    viewModel = viewModel,
                    widthSizeClass = widthSizeClass,
                    isAppInRecentsBlur = isInBackgroundCurtain,
                    deepLinkUri = pendingDeepLinkUri,
                    onDeepLinkConsumed = { pendingDeepLinkUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { pendingDeepLinkUri = it }
    }

    override fun onResume() {
        super.onResume()
        isInBackgroundCurtain = false
        viewModel.evaluateLockOnResume()
        viewModel.onAppForegrounded()
    }

    override fun onPause() {
        isInBackgroundCurtain = true
        viewModel.recordAppBackgrounded()
        super.onPause()
    }
}
