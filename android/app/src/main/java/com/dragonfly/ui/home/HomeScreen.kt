package com.dragonfly.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dragonfly.status.Overall
import com.dragonfly.status.StatusAggregate
import com.dragonfly.ui.theme.DragonflyTheme
import com.dragonfly.update.AppState
import com.dragonfly.update.UpdateFlowManager
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

@Composable
fun HomeScreen(
    onOpenApp: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenDigest: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val needsPermission by viewModel.needsInstallPermission.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    if (needsPermission) {
        AlertDialog(
            onDismissRequest = viewModel::dismissInstallPermissionDialog,
            title = { Text("Allow installs") },
            text = {
                Text(
                    "Dragonfly needs the \"Install unknown apps\" permission to update the suite. " +
                        "Grant it once and updates become a single confirmation tap."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::openUnknownSourcesSettings) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissInstallPermissionDialog) { Text("Not now") }
            },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HeaderPanel(
                    updatesAvailable = state.updatesAvailable,
                    checking = state.checking,
                    onRefresh = viewModel::refresh,
                    onOpenSettings = onOpenSettings,
                )
            }
            item {
                StatusBanner(status = state.status, onClick = onOpenStatus)
            }
            item {
                DigestBanner(onClick = onOpenDigest)
            }
            item {
                SectionHeader("Apps", modifier = Modifier.padding(top = 8.dp))
            }
            items(state.cards, key = { it.status.app.key }) { card ->
                AppCard(
                    card = card,
                    onLaunch = { viewModel.launchApp(card.status.app.key) },
                    onUpdate = { viewModel.updateApp(card.status.app.key) },
                    onOpen = { onOpenApp(card.status.app.key) },
                    onCancel = { viewModel.resetInstall(card.status.app.key) },
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(status: StatusAggregate, onClick: () -> Unit) {
    val colors = DragonflyTheme.colors
    val (dotColor, label) = when {
        status.overall == Overall.CHECKING -> colors.info.base to "Checking services…"
        status.down > 0 -> colors.warn.base to "${status.down} ${if (status.down == 1) "service" else "services"} down"
        else -> colors.ok.base to "All systems go"
    }
    PanelCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(dotColor))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Caption(
                    "Suite status · ${status.up}/${status.total} up" +
                        if (status.offNetwork > 0) " · ${status.offNetwork} off-network" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DigestBanner(onClick: () -> Unit) {
    val colors = DragonflyTheme.colors
    PanelCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        channel = colors.hub.base,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(colors.hub.base))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Your week", style = MaterialTheme.typography.titleMedium)
                Caption(
                    "The suite's weekly recap",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeaderPanel(
    updatesAvailable: Int,
    checking: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = DragonflyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.heroGradient)
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Dragonfly",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Caption("App suite hub", color = Color.White.copy(alpha = 0.8f))
                }
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, "Check all", tint = Color.White)
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, "Settings", tint = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    checking -> "Checking for updates…"
                    updatesAvailable == 0 -> "Everything is up to date"
                    updatesAvailable == 1 -> "1 update available"
                    else -> "$updatesAvailable updates available"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun AppCard(
    card: HomeUiState.CardUi,
    onLaunch: () -> Unit,
    onUpdate: () -> Unit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = DragonflyTheme.colors
    val status = card.status
    val busy = card.phase.kind != UpdateFlowManager.PhaseKind.IDLE

    PanelCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // White app logo to the left of the name.
                card.icon?.let { icon ->
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    status.app.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(status.state)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                DataText(
                    status.installedVersionName ?: "—",
                    color = if (status.state == AppState.UPDATE_AVAILABLE) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        colors.info.base
                    },
                )
                val latest = status.latest
                if (latest != null && status.state == AppState.UPDATE_AVAILABLE) {
                    Text(
                        "  →  ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DataText(latest.versionName, color = colors.warn.base)
                }
            }
            status.note?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PipelineProgress(card.phase) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            val showLaunch = status.installedVersionCode != null && !status.app.isSelf
            val showUpdate = !busy &&
                (status.state == AppState.UPDATE_AVAILABLE || status.state == AppState.NOT_INSTALLED) &&
                status.latest != null
            if (showLaunch || showUpdate) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showUpdate) {
                        PulseButton(
                            text = if (status.state == AppState.NOT_INSTALLED) "Install" else "Update",
                            onClick = onUpdate,
                            compact = true,
                        )
                    }
                    if (showLaunch) {
                        PulseButton(
                            text = "Open",
                            onClick = onLaunch,
                            compact = true,
                            tonal = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(state: AppState) {
    val colors = DragonflyTheme.colors
    val (label, color) = when (state) {
        AppState.UP_TO_DATE -> "Up to date" to colors.ok.base
        AppState.UPDATE_AVAILABLE -> "Update" to colors.warn.base
        AppState.NOT_INSTALLED -> "Not installed" to MaterialTheme.colorScheme.onSurfaceVariant
        AppState.ERROR -> "Check failed" to MaterialTheme.colorScheme.error
    }
    Caption(label, color = color)
}

@Composable
private fun PipelineProgress(phase: UpdateFlowManager.Phase) {
    val colors = DragonflyTheme.colors
    Column {
        Caption(
            when (phase.kind) {
                UpdateFlowManager.PhaseKind.DOWNLOADING -> "Downloading"
                UpdateFlowManager.PhaseKind.VERIFYING -> "Verifying SHA-256"
                UpdateFlowManager.PhaseKind.INSTALLING -> "Waiting for install"
                UpdateFlowManager.PhaseKind.IDLE -> ""
            },
            color = colors.hub.base,
        )
        Spacer(Modifier.height(6.dp))
        if (phase.kind == UpdateFlowManager.PhaseKind.DOWNLOADING && phase.progress >= 0f) {
            LinearProgressIndicator(
                progress = { phase.progress },
                modifier = Modifier.fillMaxWidth(),
                color = colors.hub.base,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.hub.base,
            )
        }
    }
}
