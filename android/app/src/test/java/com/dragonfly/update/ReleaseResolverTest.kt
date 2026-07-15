package com.dragonfly.update

import com.dragonfly.net.GitHubAsset
import com.dragonfly.net.GitHubRelease
import com.dragonfly.net.downloadUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseResolverTest {

    // --- version.json ---

    @Test
    fun `parses full version json`() {
        val parsed = ReleaseResolver.parseVersionJson(
            """{"versionCode": 42, "versionName": "1.4.2", "sha256": "abc123", "minSdk": 26}"""
        )
        assertEquals(VersionJson(42, "1.4.2", "abc123", 26), parsed)
    }

    @Test
    fun `parses minimal version json and ignores unknown keys`() {
        val parsed = ReleaseResolver.parseVersionJson(
            """{"versionCode": 7, "versionName": "0.9.0", "builtBy": "ci"}"""
        )
        assertEquals(VersionJson(7, "0.9.0", null, null), parsed)
    }

    @Test
    fun `version json without versionCode fails`() {
        assertFailsWith<Exception> {
            ReleaseResolver.parseVersionJson("""{"versionName": "1.0.0"}""")
        }
    }

    // --- asset selection ---

    private fun asset(name: String, id: Long = 1) =
        GitHubAsset(id = id, name = name, browserDownloadUrl = "https://github.com/dl/$name")

    @Test
    fun `picks apk and version json assets case-insensitively`() {
        val assets = listOf(asset("Spotter-1.4.2.APK", 10), asset("VERSION.JSON", 11), asset("notes.txt", 12))
        assertEquals(10, ReleaseResolver.pickApkAsset(assets)?.id)
        assertEquals(11, ReleaseResolver.pickVersionJsonAsset(assets)?.id)
    }

    @Test
    fun `missing assets return null`() {
        val assets = listOf(asset("notes.txt"))
        assertNull(ReleaseResolver.pickApkAsset(assets))
        assertNull(ReleaseResolver.pickVersionJsonAsset(assets))
    }

    @Test
    fun `download url switches to the api asset endpoint with a pat`() {
        val a = asset("app.apk", id = 99)
        assertEquals("https://github.com/dl/app.apk", a.downloadUrl("CDRaab01/Spotter", usePat = false))
        assertEquals(
            "https://api.github.com/repos/CDRaab01/Spotter/releases/assets/99",
            a.downloadUrl("CDRaab01/Spotter", usePat = true),
        )
    }

    // --- state diff ---

    private fun latest(code: Long) = LatestRelease(code, "x", "https://x/app.apk", "aa")

    @Test
    fun `state matrix follows versionCode`() {
        assertEquals(AppState.ERROR, ReleaseResolver.stateFor(1, null))
        assertEquals(AppState.NOT_INSTALLED, ReleaseResolver.stateFor(null, latest(5)))
        assertEquals(AppState.UPDATE_AVAILABLE, ReleaseResolver.stateFor(4, latest(5)))
        assertEquals(AppState.UP_TO_DATE, ReleaseResolver.stateFor(5, latest(5)))
        assertEquals(AppState.UP_TO_DATE, ReleaseResolver.stateFor(6, latest(5)))
    }

    // --- notes-since-installed rollup ---

    private fun rel(tag: String, body: String?, name: String? = null) =
        GitHubRelease(tagName = tag, name = name, body = body)

    // Newest-first, like the GitHub API returns them.
    private val releases = listOf(
        rel("v1.4.2", "Fixed the crash"),
        rel("v1.4.1", "Faster sync"),
        rel("v1.4.0", "New dashboard"),
        rel("v1.3.9", "Old news"),
    )

    @Test
    fun `rolls up every release newer than installed, newest first`() {
        val notes = ReleaseResolver.notesSinceInstalled(releases, "1.4.0")
        assertEquals(
            "v1.4.2\nFixed the crash\n\nv1.4.1\nFaster sync",
            notes,
        )
    }

    @Test
    fun `excludes the installed release and anything older`() {
        val notes = ReleaseResolver.notesSinceInstalled(releases, "1.4.0")!!
        assertTrue("New dashboard" !in notes) // installed
        assertTrue("Old news" !in notes) // older than installed
    }

    @Test
    fun `installed already on latest yields nothing`() {
        assertNull(ReleaseResolver.notesSinceInstalled(releases, "1.4.2"))
    }

    @Test
    fun `unknown installed version falls back to the latest release notes only`() {
        // A local build whose versionName matches no release tag.
        val notes = ReleaseResolver.notesSinceInstalled(releases, "9.9.9-debug")
        assertEquals("v1.4.2\nFixed the crash", notes)
    }

    @Test
    fun `prefers release name over tag as the header when present`() {
        val named = listOf(
            rel("v2.0.0", "Big rewrite", name = "2.0 — Big Bang"),
            rel("v1.9.0", "prior"),
        )
        val notes = ReleaseResolver.notesSinceInstalled(named, "1.9.0")
        assertEquals("2.0 — Big Bang\nBig rewrite", notes)
    }

    @Test
    fun `skips releases with blank bodies`() {
        val withBlanks = listOf(
            rel("v3.2", "Real notes"),
            rel("v3.1", "   "),
            rel("v3.0", null),
        )
        val notes = ReleaseResolver.notesSinceInstalled(withBlanks, "3.0")
        assertEquals("v3.2\nReal notes", notes)
    }

    @Test
    fun `empty release list yields null`() {
        assertNull(ReleaseResolver.notesSinceInstalled(emptyList(), "1.0.0"))
    }
}
