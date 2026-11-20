package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.BuildConfig
import com.example.R
import com.example.data.update.ApkDownloadState
import com.example.data.update.AppUpdateManager
import com.example.data.update.GitHubReleaseInfo
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily
import java.io.File

@Composable
fun AppUpdateDialog(
    release: GitHubReleaseInfo,
    downloadState: ApkDownloadState,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val installErrorText = stringResource(R.string.update_dialog_install_error)

    var needsUnknownSourcesPermission by remember(release.buildNumber) { mutableStateOf(false) }
    var installError by remember(release.buildNumber) { mutableStateOf<String?>(null) }
    var autoTriggeredFilePath by remember(release.buildNumber) { mutableStateOf<String?>(null) }

    val launchInstaller: (File) -> Unit = remember(context, installErrorText) {
        { apkFile ->
            installError = null
            if (!AppUpdateManager.canRequestPackageInstalls(context)) {
                needsUnknownSourcesPermission = true
            } else {
                needsUnknownSourcesPermission = false
                runCatching {
                    val intent = AppUpdateManager.buildInstallApkIntent(context, apkFile)
                    context.startActivity(intent)
                }.onFailure { err ->
                    installError = err.localizedMessage?.takeIf { it.isNotBlank() } ?: installErrorText
                }
            }
        }
    }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val granted = AppUpdateManager.canRequestPackageInstalls(context)
        needsUnknownSourcesPermission = !granted
        if (granted) {
            val readyFile = (downloadState as? ApkDownloadState.Downloaded)?.apkFile
            if (readyFile != null && readyFile.exists()) {
                launchInstaller(readyFile)
            }
        }
    }

    // Automatically launch system installer (or show unknown-sources prompt) as soon as APK finishes downloading
    LaunchedEffect(downloadState) {
        if (downloadState is ApkDownloadState.Downloaded) {
            val path = downloadState.apkFile.absolutePath
            if (autoTriggeredFilePath != path) {
                autoTriggeredFilePath = path
                launchInstaller(downloadState.apkFile)
            }
        } else if (downloadState is ApkDownloadState.Downloading) {
            installError = null
            needsUnknownSourcesPermission = false
        }
    }

    val isDownloading = downloadState is ApkDownloadState.Downloading

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) {
                onDismiss()
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Rounded.NewReleases,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.update_dialog_title, release.displayVersion),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(
                            R.string.update_dialog_version_compare,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                            release.displayVersion,
                            release.buildNumber,
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.update_dialog_notes_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 180.dp),
                ) {
                    Text(
                        text = release.releaseNotes.ifBlank {
                            stringResource(R.string.update_dialog_notes_empty)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }

                when (downloadState) {
                    is ApkDownloadState.Idle -> Unit

                    is ApkDownloadState.Downloading -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update_download_progress_block"),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val progress = downloadState.progress
                            if (progress != null) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = stringResource(
                                        R.string.update_dialog_downloading_progress,
                                        AppUpdateManager.formatBytes(downloadState.downloadedBytes),
                                        AppUpdateManager.formatBytes(downloadState.totalBytes),
                                        downloadState.percent ?: 0,
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = stringResource(
                                        R.string.update_dialog_downloading_indeterminate,
                                        AppUpdateManager.formatBytes(downloadState.downloadedBytes),
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    is ApkDownloadState.Downloaded -> {
                        Text(
                            text = stringResource(
                                R.string.update_dialog_downloaded_ready,
                                AppUpdateManager.formatBytes(downloadState.sizeBytes),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalStatusColors.current.connected,
                            modifier = Modifier.testTag("update_download_ready_text"),
                        )
                    }

                    is ApkDownloadState.Cancelled -> {
                        Text(
                            text = stringResource(R.string.update_dialog_download_cancelled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is ApkDownloadState.Failed -> {
                        Text(
                            text = downloadState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("update_download_error_text"),
                        )
                    }
                }

                if (needsUnknownSourcesPermission) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("update_unknown_sources_notice"),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = stringResource(R.string.update_dialog_unknown_sources_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                            Text(
                                text = stringResource(R.string.update_dialog_unknown_sources_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }

                installError?.let { errText ->
                    Text(
                        text = errText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when {
                isDownloading -> {
                    FilledTonalButton(
                        onClick = onCancelDownload,
                        modifier = Modifier.testTag("update_dialog_cancel_download_button"),
                    ) {
                        Text(text = stringResource(R.string.update_dialog_action_cancel_download))
                    }
                }

                downloadState is ApkDownloadState.Downloaded && needsUnknownSourcesPermission -> {
                    FilledTonalButton(
                        onClick = {
                            runCatching {
                                unknownSourcesLauncher.launch(
                                    AppUpdateManager.buildUnknownSourcesSettingsIntent(context),
                                )
                            }.onFailure { err ->
                                installError = err.localizedMessage ?: installErrorText
                            }
                        },
                        modifier = Modifier.testTag("update_dialog_open_settings_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.update_dialog_open_settings))
                    }
                }

                downloadState is ApkDownloadState.Downloaded -> {
                    FilledTonalButton(
                        onClick = { launchInstaller(downloadState.apkFile) },
                        modifier = Modifier.testTag("update_dialog_install_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.InstallMobile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.update_dialog_action_install))
                    }
                }

                downloadState is ApkDownloadState.Failed -> {
                    FilledTonalButton(
                        onClick = onStartDownload,
                        modifier = Modifier.testTag("update_dialog_confirm_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.update_dialog_action_retry))
                    }
                }

                else -> {
                    FilledTonalButton(
                        onClick = onStartDownload,
                        modifier = Modifier.testTag("update_dialog_confirm_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.update_dialog_action_update))
                    }
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("update_dialog_later_button"),
                ) {
                    Text(text = stringResource(R.string.update_dialog_action_later))
                }
            }
        },
    )
}
