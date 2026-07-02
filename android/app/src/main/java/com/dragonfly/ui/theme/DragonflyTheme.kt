package com.dragonfly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import design.pulse.ui.theme.LocalDataTypography
import design.pulse.ui.theme.LocalSpacing
import design.pulse.ui.theme.PulseAccent
import design.pulse.ui.theme.PulseChannel
import design.pulse.ui.theme.PulseDataTypography
import design.pulse.ui.theme.PulseTheme
import design.pulse.ui.theme.Spacing
import design.pulse.ui.theme.darkAmberChannel
import design.pulse.ui.theme.darkBlueChannel
import design.pulse.ui.theme.darkGreenChannel
import design.pulse.ui.theme.darkPulseStructure
import design.pulse.ui.theme.darkVioletChannel
import design.pulse.ui.theme.lightAmberChannel
import design.pulse.ui.theme.lightBlueChannel
import design.pulse.ui.theme.lightGreenChannel
import design.pulse.ui.theme.lightPulseStructure
import design.pulse.ui.theme.lightVioletChannel

/**
 * Dragonfly's semantic layer over PULSE — the hub's channel map:
 *  - hub:  violet — the lead voice (Spotter/Plate lead blue, Cookbook amber; violet was free
 *          and reads "system"): identity, primary actions
 *  - info: blue   — version readouts, neutral data
 *  - ok:   green  — up to date, successful installs
 *  - warn: amber  — update available, attention states
 * Structure (hairlines/panels/glow) and the hero gradient ride along so screens have one stop.
 */
@Immutable
data class DragonflyColors(
    val hub: PulseChannel,
    val info: PulseChannel,
    val ok: PulseChannel,
    val warn: PulseChannel,
    val hairline: Color,
    val hairlineStrong: Color,
    val panel: Color,
    val panelHigh: Color,
    val glow: Color,
    /** Indigo → violet, Dragonfly's lead voice. */
    val heroGradient: Brush,
)

private fun dragonflyColors(dark: Boolean): DragonflyColors {
    val structure = if (dark) darkPulseStructure(PulseAccent.Violet) else lightPulseStructure(PulseAccent.Violet)
    return DragonflyColors(
        hub = if (dark) darkVioletChannel() else lightVioletChannel(),
        info = if (dark) darkBlueChannel() else lightBlueChannel(),
        ok = if (dark) darkGreenChannel() else lightGreenChannel(),
        warn = if (dark) darkAmberChannel() else lightAmberChannel(),
        hairline = structure.hairline,
        hairlineStrong = structure.hairlineStrong,
        panel = structure.panel,
        panelHigh = structure.panelHigh,
        glow = structure.glow,
        heroGradient = structure.heroGradient,
    )
}

val LocalDragonflyColors = staticCompositionLocalOf { dragonflyColors(dark = true) }

@Composable
fun DragonflyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    PulseTheme(darkTheme = darkTheme, accent = PulseAccent.Violet) {
        CompositionLocalProvider(
            LocalDragonflyColors provides dragonflyColors(darkTheme),
        ) {
            content()
        }
    }
}

/** Convenience accessors mirroring `MaterialTheme.*`. */
object DragonflyTheme {
    val colors: DragonflyColors
        @Composable @ReadOnlyComposable get() = LocalDragonflyColors.current
    val dataType: PulseDataTypography
        @Composable @ReadOnlyComposable get() = LocalDataTypography.current
    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
}
