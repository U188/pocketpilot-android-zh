package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.HostKeyPrompt
import com.example.ui.theme.MonoFontFamily

@Composable
fun HostKeyVerificationDialog(
    prompt: HostKeyPrompt,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    val isChanged = prompt.isKeyChanged
    val accentColor = if (isChanged) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    val containerBg = if (isChanged) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                imageVector = if (isChanged) Icons.Rounded.GppBad else Icons.Rounded.VerifiedUser,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text(
                text = stringResource(
                    if (isChanged) R.string.host_key_changed_title else R.string.host_key_new_title,
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = if (isChanged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (isChanged) R.string.host_key_changed_message else R.string.host_key_new_message,
                        prompt.host,
                        prompt.port,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(containerBg)
                        .border(
                            width = 1.dp,
                            color = accentColor.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.host_key_algorithm, prompt.algorithm),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = stringResource(R.string.host_key_new_fingerprint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = prompt.sha256Fingerprint,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    if (isChanged && prompt.existingFingerprint != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.host_key_old_fingerprint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = prompt.existingFingerprint,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onTrust,
                colors = if (isChanged) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.testTag("host_key_trust_button"),
            ) {
                Text(
                    text = stringResource(
                        if (isChanged) R.string.host_key_replace else R.string.host_key_trust,
                    ),
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.testTag("host_key_reject_button"),
            ) {
                Text(text = stringResource(R.string.host_key_reject))
            }
        },
    )
}
