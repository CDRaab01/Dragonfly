package com.dragonfly.status

import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetSnapshotTest {

    private fun service(
        key: String,
        group: ServiceGroup,
    ) = MonitoredService(
        key = key,
        displayName = key.replaceFirstChar { it.uppercase() },
        group = group,
        baseUrl = "https://$key.example",
        probe = ProbeType.SUITE,
        reachability = Reachability.PUBLIC,
    )

    @Test
    fun `maps each probe result to key, name, group and state name`() {
        val statuses = listOf(
            ServiceStatus(service("spotter", ServiceGroup.SUITE), ServiceState.UP),
            ServiceStatus(service("magpie", ServiceGroup.SUITE), ServiceState.OFF_NETWORK),
            ServiceStatus(service("plex", ServiceGroup.MEDIA), ServiceState.DOWN),
        )

        val snapshot = buildWidgetSnapshot(statuses)

        assertEquals(3, snapshot.services.size)
        assertEquals("spotter", snapshot.services[0].key)
        assertEquals("Spotter", snapshot.services[0].displayName)
        assertEquals("SUITE", snapshot.services[0].group)
        assertEquals("UP", snapshot.services[0].state)
        assertEquals("OFF_NETWORK", snapshot.services[1].state)
        assertEquals("MEDIA", snapshot.services[2].group)
        assertEquals("DOWN", snapshot.services[2].state)
    }

    @Test
    fun `empty probe list yields an empty snapshot`() {
        assertEquals(0, buildWidgetSnapshot(emptyList()).services.size)
    }
}
