package com.dragonfly.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dragonfly.settings.UpdateSource
import com.dragonfly.ui.theme.DragonflyTheme
import com.dragonfly.update.AppState
import com.dragonfly.update.UpdateFlowManager
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    onBack: () -> Unit,
    viewModel: AppDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val needsPermission by viewModel.needsInstallPermission.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val colors = DragonflyTheme.colors

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
            text = { Text("Dragonfly needs the \"Install unknown apps\" permission to update the suite.") },
            confirmButton = {
                TextButton(onClick = viewModel::openUnknownSourcesSettings) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissInstallPermissionDialog) { Text("Not now") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(viewModel.app.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        SectionHeader("Version")
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Caption("Installed")
                                DataText(
                                    state.status?.installedVersionName ?: "—",
                                    color = colors.info.base,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Caption("Latest")
                                DataText(
                                    state.status?.latest?.versionName ?: "—",
                                    color = if (state.status?.state == AppState.UPDATE_AVAILABLE) {
                                        colors.warn.base
                                    } else {
                                        colors.ok.base
                                    },
                                )
                            }
                        }
                        state.status?.note?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.phase.kind != UpdateFlowManager.PhaseKind.IDLE) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = colors.hub.base,
                            )
                        } else if (
                            state.status?.state == AppState.UPDATE_AVAILABLE ||
                            state.status?.state == AppState.NOT_INSTALLED
                        ) {
                            Spacer(Modifier.height(12.dp))
                            PulseButton(
                                text = if (state.status?.state == AppState.NOT_INSTALLED) "Install" else "Update",
                                onClick = viewModel::update,
                                compact = true,
                            )
                        }
                    }
                }
            }

            item {
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        SectionHeader("Update source")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.sourceOverride == null,
                                onClick = { viewModel.setSourceOverride(null) },
                                label = { Text("Default") },
                            )
                            FilterChip(
                                selected = state.sourceOverride == UpdateSource.GITHUB,
                                onClick = { viewModel.setSourceOverride(UpdateSource.GITHUB) },
                                label = { Text("GitHub") },
                                enabled = viewModel.app.githubRepo != null,
                            )
                            FilterChip(
                                selected = state.sourceOverride == UpdateSource.SELF_HOST,
                                onClick = { viewModel.setSourceOverride(UpdateSource.SELF_HOST) },
                                label = { Text("Self-host") },
                            )
                        }
                    }
                }
            }

            // When an update is available and we're several releases behind, `changesSince` rolls up
            // every release's notes since the installed build; otherwise fall back to the latest note.
            val rollup = state.changesSince
            val changelog = rollup ?: state.status?.latest?.changelog
            if (changelog != null) {
                val installedName = state.status?.installedVersionName
                val header = if (rollup != null && installedName != null) {
                    "Changes since $installedName"
                } else {
                    "What's new"
                }
                item {
                    PanelCard(Modifier.fillMaxWidth()) {
                        Column {
                            SectionHeader(header)
                            Spacer(Modifier.height(8.dp))
                            Text(changelog, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (state.history.isNotEmpty()) {
                item { SectionHeader("Update history", Modifier.padding(top = 8.dp)) }
                items(state.history) { record ->
                    PanelCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                DataText(record.versionName)
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                        .format(Date(record.timestampMs)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                record.message?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Caption(
                                if (record.success) "Installed" else "Failed",
                                color = if (record.success) colors.ok.base else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
