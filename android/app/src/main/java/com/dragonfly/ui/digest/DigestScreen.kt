package com.dragonfly.ui.digest

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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dragonfly.digest.DigestDomain
import com.dragonfly.digest.DigestFormatter
import com.dragonfly.digest.DigestResult
import com.dragonfly.digest.WeeklyDigest
import com.dragonfly.ui.theme.DragonflyTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import java.time.Instant

@Composable
fun DigestScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DigestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

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
                    "Your week",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.loading) {
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val result = state.result) {
                null -> if (!state.loading) {
                    // Shouldn't happen (init loads), but never render blank.
                    CenteredMessage("No digest loaded", "Tap refresh to try again.")
                }
                is DigestResult.Success -> DigestContent(result.digest)
                is DigestResult.NotYet -> CenteredMessage(
                    "No digest yet",
                    "Check back Sunday evening — the suite's weekly recap lands then.",
                )
                is DigestResult.NeedsKey -> CenteredMessage(
                    "Add your digest key",
                    "Open Settings and paste your weekly-digest key to unlock this recap.",
                    actionLabel = "Open Settings",
                    onAction = onOpenSettings,
                )
                is DigestResult.Error -> CenteredMessage(
                    "Couldn't load your week",
                    result.message,
                    actionLabel = "Retry",
                    onAction = viewModel::refresh,
                )
            }
        }
    }
}

@Composable
private fun DigestContent(digest: WeeklyDigest) {
    val colors = DragonflyTheme.colors
    val now = Instant.now()
    val range = DigestFormatter.weekRange(digest.weekStart, digest.weekEnd)
        ?: listOfNotNull(digest.weekStart, digest.weekEnd).joinToString(" – ").ifBlank { "This week" }
    val updated = DigestFormatter.updatedLabel(digest.generatedAt, now)
    val visible = DigestFormatter.visibleDomains(digest)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(range, style = MaterialTheme.typography.headlineSmall)
                if (updated != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        updated,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The warm narrative rides in the hub (violet) voice — only when the LM produced one.
        digest.narrative?.takeIf { it.isNotBlank() }?.let { narrative ->
            item {
                PanelCard(modifier = Modifier.fillMaxWidth(), channel = colors.hub.base) {
                    Caption("This week", color = colors.hub.base)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        narrative,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                PanelCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No app data reached the digest this week.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                SectionHeader("Highlights", modifier = Modifier.padding(top = 4.dp))
            }
            visible.forEach { domain ->
                item(key = domain.name) { HeadlineCard(domain, digest) }
            }
        }
    }
}

@Composable
private fun HeadlineCard(domain: DigestDomain, digest: WeeklyDigest) {
    val colors = DragonflyTheme.colors
    val d = digest.domains
    val (title, headline, valueColor) = when (domain) {
        DigestDomain.TRAINING ->
            Triple("Training", d.training?.let { DigestFormatter.trainingHeadline(it) }.orEmpty(), colors.info.base)
        DigestDomain.NUTRITION ->
            Triple("Nutrition", d.nutrition?.let { DigestFormatter.nutritionHeadline(it) }.orEmpty(), colors.info.base)
        DigestDomain.COOKING ->
            Triple("Cooking", d.cooking?.let { DigestFormatter.cookingHeadline(it) }.orEmpty(), colors.info.base)
        DigestDomain.MONEY -> {
            val money = d.money
            val positive = money?.let { DigestFormatter.isNetPositive(it) } ?: true
            Triple(
                "Money",
                money?.let { DigestFormatter.moneyHeadline(it) }.orEmpty(),
                if (positive) colors.ok.base else MaterialTheme.colorScheme.error,
            )
        }
    }

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Caption(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            DataText(headline, style = MaterialTheme.typography.titleMedium, color = valueColor)
        }
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            design.pulse.ui.components.PulseButton(text = actionLabel, onClick = onAction, compact = true)
        }
    }
}
