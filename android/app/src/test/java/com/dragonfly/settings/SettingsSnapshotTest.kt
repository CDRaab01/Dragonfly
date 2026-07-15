package com.dragonfly.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsSnapshotTest {

    @Test
    fun `broker server url returns the set value or null (sibling fallback)`() {
        val s = SettingsSnapshot(perAppServerUrl = mapOf("plate" to "https://plate.example/"))
        assertEquals("https://plate.example/", s.serverUrlFor("plate"))
        // Unset app: null → the sibling keeps its own configured URL.
        assertNull(s.serverUrlFor("spotter"))
        // Blank is treated as "no opinion", not an empty override.
        assertNull(SettingsSnapshot(perAppServerUrl = mapOf("plate" to "  ")).serverUrlFor("plate"))
    }
}
