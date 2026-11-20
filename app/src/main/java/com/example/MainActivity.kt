package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.crash.CrashReporter
import com.example.domain.model.AppSettings
import com.example.domain.model.SshConnectionState
import com.example.security.BiometricAuthHelper
import com.example.ui.components.AppUpdateDialog
import com.example.ui.crash.CrashReportScreen
import com.example.ui.navigation.OpenCodeNavHost
import com.example.ui.security.BiometricLockScreen
import com.example.ui.theme.OpenCodeTheme

class MainActivity : FragmentActivity() {

    private var lastBackgroundedElapsedMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpenCodeApplication).container

        setContent {
            val settings by container.preferencesRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val connectionState by container.sshConnectionManager.connectionState
                .collectAsStateWithLifecycle()
            val updateState by container.appUpdateManager.state
                .collectAsStateWithLifecycle()

            var pendingCrash by remember {
                mutableStateOf(CrashReporter.readPendingCrash(this@MainActivity))
            }
            var isLocked by remember { mutableStateOf(false) }
            var initialLockChecked by remember { mutableStateOf(false) }
            var authErrorMessage by remember { mutableStateOf<String?>(null) }
            var askedNotifThisSession by remember { mutableStateOf(false) }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { /* Graceful: FGS still keeps SSH alive even if notification is denied */ },
            )

            // Apply or clear FLAG_SECURE dynamically
            LaunchedEffect(settings.secureScreenEnabled) {
                if (settings.secureScreenEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // Lock on cold launch if biometric lock is enabled (skip while showing crash report)
            LaunchedEffect(settings.biometricLockEnabled, pendingCrash) {
                if (pendingCrash != null) return@LaunchedEffect
                if (!initialLockChecked && settings.biometricLockEnabled) {
                    initialLockChecked = true
                    if (BiometricAuthHelper.canAuthenticate(this@MainActivity)) {
                        isLocked = true
                    }
                } else if (!settings.biometricLockEnabled) {
                    isLocked = false
                }
            }

            // Check GitHub Releases on startup (throttled to once every 6 hours, respects toggle)
            LaunchedEffect(pendingCrash) {
                if (pendingCrash == null) {
                    container.appUpdateManager.checkForUpdates(force = false)
                }
            }

            // Request POST_NOTIFICATIONS once on Android 13+ after SSH connects with keep-alive enabled
            LaunchedEffect(connectionState, settings.keepAliveInBackground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    settings.keepAliveInBackground &&
                    !askedNotifThisSession &&
                    connectionState is SshConnectionState.Connected
                ) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        askedNotifThisSession = true
                        runCatching {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, settings.biometricLockEnabled, settings.biometricLockTimeoutSec) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            container.agentNotificationHelper.setAppInForeground(false)
                            lastBackgroundedElapsedMs = SystemClock.elapsedRealtime()
                        }

                        Lifecycle.Event.ON_START -> {
                            container.agentNotificationHelper.setAppInForeground(true)
                            if (settings.biometricLockEnabled &&
                                lastBackgroundedElapsedMs > 0L &&
                                BiometricAuthHelper.canAuthenticate(this@MainActivity)
                            ) {
                                val elapsedSec = (SystemClock.elapsedRealtime() - lastBackgroundedElapsedMs) / 1000L
                                if (elapsedSec >= settings.biometricLockTimeoutSec) {
                                    isLocked = true
                                }
                            }
                        }

                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val triggerBiometricPrompt = remember {
                {
                    authErrorMessage = null
                    BiometricAuthHelper.authenticate(
                        activity = this@MainActivity,
                        title = getString(R.string.biometric_prompt_title),
                        subtitle = getString(R.string.biometric_prompt_subtitle),
                        onSuccess = {
                            authErrorMessage = null
                            isLocked = false
                        },
                        onError = { err ->
                            authErrorMessage = err.ifBlank { getString(R.string.biometric_auth_failed) }
                        },
                    )
                }
            }

            LaunchedEffect(isLocked) {
                if (isLocked) {
                    triggerBiometricPrompt()
                }
            }

            OpenCodeTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val crash = pendingCrash
                    if (crash != null) {
                        CrashReportScreen(
                            report = crash,
                            onContinue = {
                                CrashReporter.clearPendingCrash(this@MainActivity)
                                pendingCrash = null
                            },
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            OpenCodeNavHost(container = container)

                            if (isLocked && settings.biometricLockEnabled) {
                                BiometricLockScreen(
                                    errorMessage = authErrorMessage,
                                    onUnlockClick = triggerBiometricPrompt,
                                )
                            } else if (updateState.showUpdateDialog && updateState.availableRelease != null) {
                                val release = updateState.availableRelease!!
                                AppUpdateDialog(
                                    release = release,
                                    downloadState = updateState.downloadState,
                                    onStartDownload = { container.appUpdateManager.startDownload(release) },
                                    onCancelDownload = container.appUpdateManager::cancelDownload,
                                    onDismiss = container.appUpdateManager::dismissUpdateDialog,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
