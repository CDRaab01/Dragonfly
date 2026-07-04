package com.dragonfly.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dragonfly.status.ProbeType
import com.dragonfly.status.ServiceState
import com.dragonfly.status.ServiceStatus
import com.dragonfly.status.StatusResolver
import com.dragonfly.ui.theme.DragonflyTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import java.time.Instant

@Composable
fun SuiteStatusScreen(
    onBack: () -> Unit,
    viewModel: SuiteStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val now = Instant.now()

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Suite status",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { OverallBanner(state) }
            state.groups.forEach { group ->
                item(key = "hdr-${group.group.name}") {
                    SectionHeader(group.group.label, modifier = Modifier.padding(top = 8.dp))
                }
                items(group.rows, key = { it.service.key }) { row ->
                    ServiceRow(row, now)
                }
            }
        }
    }
}

@Composable
private fun OverallBanner(state: StatusUiState) {
    val colors = DragonflyTheme.colors
    val agg = state.aggregate
    val (dot, label) = when {
        state.checking && agg.down == 0 -> colors.info.base to "Checking services…"
        agg.down > 0 -> colors.warn.base to "${agg.down} ${if (agg.down == 1) "service" else "services"} down"
        else -> colors.ok.base to "All systems go"
    }
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(dot)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                val sub = buildString {
                    append("${agg.up}/${agg.total} up")
                    if (agg.offNetwork > 0) append(" · ${agg.offNetwork} off-network")
                }
                Caption(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ServiceRow(status: ServiceStatus, now: Instant) {
    val (label, color) = stateLabel(status.state)
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color)
                Spacer(Modifier.width(10.dp))
                Text(
                    status.service.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Caption(label, color = color)
            }
            val version = status.version
            val secondary: (@Composable () -> Unit)? = when {
                version != null -> {
                    {
                        Row(verticalAlignment = Alignment.Bottom) {
                            val commit = version.commit?.let { " · $it" } ?: ""
                            DataText(version.version + commit, color = DragonflyTheme.colors.info.base)
                            version.deployedAt?.let {
                                Text(
                                    "   deployed ${StatusResolver.relativeTime(it, now)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                // Reachability services (and any suite backend that's down) show when last checked.
                status.service.probe == ProbeType.REACHABILITY || status.checkedAt != null -> {
                    {
                        val checked = status.checkedAt?.let { "checked ${StatusResolver.relativeTime(it, now)}" } ?: ""
                        Text(
                            checked,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> null
            }
            if (secondary != null) {
                Spacer(Modifier.height(6.dp))
                secondary()
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
}

@Composable
private fun stateLabel(state: ServiceState): Pair<String, Color> {
    val colors = DragonflyTheme.colors
    return when (state) {
        ServiceState.UP -> "Up" to colors.ok.base
        ServiceState.DOWN -> "Down" to MaterialTheme.colorScheme.error
        ServiceState.OFF_NETWORK -> "Off-network" to MaterialTheme.colorScheme.onSurfaceVariant
        ServiceState.CHECKING -> "Checking" to colors.info.base
        ServiceState.UNKNOWN -> "Unknown" to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
