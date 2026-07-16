package com.dragonfly.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dragonfly.MainActivity
import com.dragonfly.status.StatusSnapshotStore
import com.dragonfly.status.WidgetServiceStatus
import com.dragonfly.status.WidgetStatusSnapshot
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Hilt can't inject Glance objects; the widget pulls the snapshot store via an EntryPoint. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun statusSnapshotStore(): StatusSnapshotStore
}

private fun entryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

class DragonflyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DragonflyWidget()
}

/**
 * The home-screen "suite at a glance": each suite app with a small status dot — green up, red down,
 * dim off-network/unknown — read from the hub's last-known probe snapshot (no network of its own,
 * so it shows the same truth as the app, offline included). Tap opens the hub. Media services are
 * intentionally left off; this is the *app* glance.
 */
class DragonflyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = entryPoint(context).statusSnapshotStore().read()
        provideContent {
            GlanceTheme { WidgetBody(snapshot) }
        }
    }
}

// PULSE-adjacent colors, hardcoded: Glance can't consume the Compose theme objects. Violet leads
// (the hub's accent, PulseViolet 0xFF8B7CFF); ink bg + status dots match the Magpie widget.
private val InkBg = Color(0xFF10151A)
private val Violet = Color(0xFF8B7CFF)
private val Green = Color(0xFF4ED08A)
private val Red = Color(0xFFE0616A)
private val TextPrimary = Color(0xFFE7EAF0)
private val TextDim = Color(0xFF9AA3B2)

/** Media rows are dropped: the widget is the suite-app glance, not the whole media stack. */
private fun WidgetStatusSnapshot.appRows(): List<WidgetServiceStatus> =
    services.filter { it.group != "MEDIA" }

private fun dotColor(state: String): Color = when (state) {
    "UP" -> Green
    "DOWN" -> Red
    else -> TextDim // OFF_NETWORK / CHECKING / UNKNOWN — dim, never a false-alarm red
}

@Composable
private fun WidgetBody(snapshot: WidgetStatusSnapshot?) {
    val rows = snapshot?.appRows().orEmpty()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(InkBg))
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Dragonfly",
                style = TextStyle(color = ColorProvider(Violet), fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            if (rows.isNotEmpty()) {
                Text(headline(rows), style = TextStyle(color = ColorProvider(TextDim), fontSize = 12.sp))
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (rows.isEmpty()) {
            Text(
                "Open Dragonfly to check",
                style = TextStyle(color = ColorProvider(TextDim), fontSize = 13.sp),
            )
            return@Column
        }
        rows.forEach { ServiceRow(it) }
    }
}

/** "All systems go" / "N down" derived from the shown rows only, so header and dots never disagree. */
private fun headline(rows: List<WidgetServiceStatus>): String {
    val down = rows.count { it.state == "DOWN" }
    return when {
        down > 0 -> "$down down"
        rows.any { it.state == "CHECKING" } -> "checking…"
        else -> "all good"
    }
}

@Composable
private fun ServiceRow(row: WidgetServiceStatus) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.size(9.dp).background(ColorProvider(dotColor(row.state)))) {}
        Spacer(GlanceModifier.width(9.dp))
        Text(
            row.displayName,
            style = TextStyle(color = ColorProvider(TextPrimary), fontSize = 14.sp),
        )
    }
}
