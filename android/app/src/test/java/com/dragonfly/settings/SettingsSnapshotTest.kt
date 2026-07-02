package com.dragonfly.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSnapshotTest {

    @Test
    fun `per-app override wins over the global default`() {
        val settings = SettingsSnapshot(
            globalSource = UpdateSource.GITHUB,
            perAppSource = mapOf("plate" to UpdateSource.SELF_HOST),
        )
        assertEquals(UpdateSource.SELF_HOST, settings.sourceFor("plate"))
        assertEquals(UpdateSource.GITHUB, settings.sourceFor("spotter"))
    }

    @Test
    fun `manifest url tolerates a trailing slash`() {
        val base = "https://dragonfly.tail1234.ts.net"
        assertEquals("$base/manifest.json", SettingsSnapshot(selfHostBaseUrl = base).manifestUrl)
        assertEquals("$base/manifest.json", SettingsSnapshot(selfHostBaseUrl = "$base/").manifestUrl)
    }

    @Test
    fun `self-host configured only when a url is set`() {
        assertFalse(SettingsSnapshot(selfHostBaseUrl = "").selfHostConfigured)
        assertFalse(SettingsSnapshot(selfHostBaseUrl = "   ").selfHostConfigured)
        assertTrue(SettingsSnapshot(selfHostBaseUrl = "https://x").selfHostConfigured)
    }
}
