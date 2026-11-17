package com.example.ui.screens.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.R
import com.example.ui.components.PinKeypad

@Composable
fun AppLockScreen(
    pinEnabled: Boolean,
    biometricEnabled: Boolean,
    onVerifyPin: (String) -> Boolean,
    onBiometricAuthenticated: () -> Unit,
) {
    val context = LocalContext.current
    var enteredPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val canUseBiometric = remember(biometricEnabled) {
        if (!biometricEnabled) false
        else {
            val bm = BiometricManager.from(context)
            bm.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == BiometricManager.BIOMETRIC_SUCCESS
        }
    }

    fun launchBiometricPrompt() {
        val activity = context as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onBiometricAuthenticated()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    errorMsg = errString.toString()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Enclave")
            .setSubtitle("Verify identity to access encrypted conversations")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(canUseBiometric) {
        if (canUseBiometric && !pinEnabled) {
            launchBiometricPrompt()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(24.dp)
            .testTag("app_lock_screen")
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.lock_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))

        if (pinEnabled) {
            PinKeypad(
                pinLength = enteredPin.length,
                showBiometric = canUseBiometric,
                onDigit = { digit ->
                    if (enteredPin.length < 6) {
                        val next = enteredPin + digit
                        enteredPin = next
                        errorMsg = null
                        if (next.length >= 4) {
                            if (onVerifyPin(next)) {
                                enteredPin = ""
                            } else if (next.length == 6) {
                                errorMsg = "Incorrect PIN"
                                enteredPin = ""
                            }
                        }
                    }
                },
                onBackspace = {
                    if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                },
                onBiometricClick = { launchBiometricPrompt() }
            )
            if (enteredPin.length in 4..5) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = {
                    if (!onVerifyPin(enteredPin)) {
                        errorMsg = "Incorrect PIN"
                        enteredPin = ""
                    }
                }) {
                    Text("Unlock")
                }
            }
        } else {
            Button(onClick = { launchBiometricPrompt() }) {
                Text("Authenticate to Unlock")
            }
        }

        errorMsg?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
