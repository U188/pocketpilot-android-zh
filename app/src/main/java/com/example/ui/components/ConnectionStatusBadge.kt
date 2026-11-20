package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.SshConnectionState
import com.example.ui.theme.LocalStatusColors

@Composable
fun ConnectionStatusDot(
    state: SshConnectionState,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalStatusColors.current
    val (color, pulsing) = when (state) {
        is SshConnectionState.Connected -> statusColors.connected to false
        is SshConnectionState.Connecting,
        is SshConnectionState.AwaitingHostKeyVerification,
        is SshConnectionState.Reconnecting -> statusColors.warning to true
        is SshConnectionState.Error -> MaterialTheme.colorScheme.error to false
        SshConnectionState.Disconnected -> statusColors.disconnected to false
    }

    val pulseAlpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "status_pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_alpha",
        )
        value
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(10.dp)
            .alpha(pulseAlpha)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun ConnectionStatusBadge(
    state: SshConnectionState,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalStatusColors.current
    val (label, accentColor) = when (state) {
        is SshConnectionState.Connected ->
            stringResource(R.string.status_connected) to statusColors.connected
        is SshConnectionState.Connecting ->
            stringResource(R.string.status_connecting) to statusColors.warning
        is SshConnectionState.AwaitingHostKeyVerification ->
            stringResource(R.string.status_verifying_host) to statusColors.warning
        is SshConnectionState.Reconnecting ->
            stringResource(R.string.status_reconnecting, state.attempt) to statusColors.warning
        is SshConnectionState.Error ->
            stringResource(R.string.status_error) to MaterialTheme.colorScheme.error
        SshConnectionState.Disconnected ->
            stringResource(R.string.status_disconnected) to statusColors.disconnected
    }

    StatusChip(
        label = label,
        accentColor = accentColor,
        dot = { ConnectionStatusDot(state = state) },
        modifier = modifier,
    )
}

@Composable
fun StatusChip(
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    dot: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accentColor.copy(alpha = 0.14f))
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.35f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) {
            dot()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
