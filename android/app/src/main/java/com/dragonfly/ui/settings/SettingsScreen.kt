package com.dragonfly.ui.settings

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dragonfly.BuildConfig
import com.dragonfly.settings.CheckInterval
import com.dragonfly.settings.UpdateSource
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val hasPat by viewModel.hasPat.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                        SectionHeader("Default update source")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.globalSource == UpdateSource.GITHUB,
                                onClick = { viewModel.setGlobalSource(UpdateSource.GITHUB) },
                                label = { Text("GitHub") },
                            )
                            FilterChip(
                                selected = settings.globalSource == UpdateSource.SELF_HOST,
                                onClick = { viewModel.setGlobalSource(UpdateSource.SELF_HOST) },
                                label = { Text("Self-host") },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Per-app overrides live on each app's detail screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                var url by rememberSaveable(settings.selfHostBaseUrl) {
                    mutableStateOf(settings.selfHostBaseUrl)
                }
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        SectionHeader("Self-host")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Base URL") },
                            placeholder = { Text("https://dragonfly.<tailnet>.ts.net") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Serves manifest.json + APKs over Tailscale Serve (HTTPS).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (url != settings.selfHostBaseUrl) {
                            Spacer(Modifier.height(8.dp))
                            PulseButton(
                                text = "Save",
                                onClick = { viewModel.setSelfHostBaseUrl(url) },
                                compact = true,
                            )
                        }
                    }
                }
            }

            item {
                var pat by remember { mutableStateOf("") }
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        SectionHeader("GitHub token")
                        Spacer(Modifier.height(8.dp))
                        Caption(if (hasPat) "Token stored (encrypted)" else "No token — public repos only")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pat,
                            onValueChange = { pat = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Personal access token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PulseButton(
                                text = "Save",
                                onClick = {
                                    viewModel.savePat(pat)
                                    pat = ""
                                },
                                compact = true,
                                enabled = pat.isNotBlank(),
                            )
                            if (hasPat) {
                                PulseButton(
                                    text = "Clear",
                                    onClick = viewModel::clearPat,
                                    compact = true,
                                    tonal = true,
                                )
                            }
                        }
                    }
                }
            }

            item {
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        SectionHeader("Auto-check")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.checkInterval == CheckInterval.MANUAL,
                                onClick = { viewModel.setCheckInterval(CheckInterval.MANUAL) },
                                label = { Text("Manual") },
                            )
                            FilterChip(
                                selected = settings.checkInterval == CheckInterval.ON_LAUNCH,
                                onClick = { viewModel.setCheckInterval(CheckInterval.ON_LAUNCH) },
                                label = { Text("On launch") },
                            )
                            FilterChip(
                                selected = settings.checkInterval == CheckInterval.DAILY,
                                onClick = { viewModel.setCheckInterval(CheckInterval.DAILY) },
                                label = { Text("Daily") },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Wi-Fi-only downloads", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Skip APK downloads on metered networks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.wifiOnly,
                                onCheckedChange = viewModel::setWifiOnly,
                            )
                        }
                    }
                }
            }

            item {
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        SectionHeader("About")
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Dragonfly",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            DataText(BuildConfig.VERSION_NAME)
                        }
                        Text(
                            "Updates itself through the same pipeline — it's on the Home list too.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
