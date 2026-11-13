package com.dragonfly.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSourceTest {

    @Test
    fun `blank or bad url falls back to github`() {
        assertEquals(ReleaseSource.GitHub, ReleaseSource.from(null))
        assertEquals(ReleaseSource.GitHub, ReleaseSource.from("  "))
        assertEquals(ReleaseSource.GitHub, ReleaseSource.from("not a url"))
    }

    @Test
    fun `forgejo api base is root plus api v1`() {
        val s = ReleaseSource.from("https://forgejo.example.ts.net") as ReleaseSource.Forgejo
        assertEquals("https://forgejo.example.ts.net/api/v1/", s.apiBaseUrl)
        val withPort = ReleaseSource.from("http://192.168.4.57:3000/") as ReleaseSource.Forgejo
        assertEquals("https://192.168.4.57:3000/api/v1/", withPort.apiBaseUrl)
    }

    @Test
    fun `forgejo download url is rehomed onto the configured root`() {
        val s = ReleaseSource.from("https://forgejo.example.ts.net/") as ReleaseSource.Forgejo
        val asset = GitHubAsset(
            id = 7,
            name = "tote-v1.2.apk",
            browserDownloadUrl = "http://192.168.4.57:3000/CDRaab01/Tote/releases/download/v1.2/tote-v1.2.apk",
        )
        assertEquals(
            "https://forgejo.example.ts.net/CDRaab01/Tote/releases/download/v1.2/tote-v1.2.apk",
            asset.downloadUrl("CDRaab01/Tote", usePat = true, source = s),
        )
    }

    @Test
    fun `github behaviour is unchanged`() {
        val asset = GitHubAsset(id = 99, name = "app.apk", browserDownloadUrl = "https://github.com/dl/app.apk")
        assertEquals("https://github.com/dl/app.apk", asset.downloadUrl("CDRaab01/Spotter", usePat = false))
        assertTrue(asset.downloadUrl("CDRaab01/Spotter", usePat = true).startsWith("https://api.github.com/"))
    }

    @Test
    fun `each host gets only its own token`() {
        val fj = ReleaseSource.from("https://dragonfly.example.ts.net:8452")
        fun h(url: String, src: ReleaseSource = fj) =
            ReleaseSource.authHeaderFor(url.toHttpUrl(), src, githubPat = "gh", forgejoToken = "fj")
        assertEquals("token fj", h("https://dragonfly.example.ts.net:8452/api/v1/repos/a/b/releases/latest"))
        assertEquals("Bearer gh", h("https://api.github.com/repos/a/b/releases/latest"))
        // GitHub keeps its PAT with Forgejo set (per-repo fallback) and with no Forgejo at all.
        assertEquals("Bearer gh", h("https://github.com/a/b", ReleaseSource.GitHub))
        // Same host, other port (another suite app behind Tailscale Serve): no token.
        assertNull(h("https://dragonfly.example.ts.net:8448/health"))
        // Forgejo token never goes anywhere when GitHub is the source.
        assertNull(h("https://dragonfly.example.ts.net:8452/x", ReleaseSource.GitHub))
        assertNull(h("https://objects.githubusercontent.com/asset"))
    }

    @Test
    fun `root is always https`() {
        fun root(u: String) = (ReleaseSource.from(u) as ReleaseSource.Forgejo).root.toString()
        assertEquals("https://dragonfly.example.ts.net:8452/", root("http://dragonfly.example.ts.net:8452"))
        assertEquals("https://dragonfly.example.ts.net:8452/", root("dragonfly.example.ts.net:8452"))
        assertEquals("https://forgejo.example.org/", root("http://forgejo.example.org"))
        assertEquals("https://forgejo.example.org/", root(" https://forgejo.example.org/ "))
    }

    @Test
    fun `fallback on missing repo or unreachable server, not on a refused token`() {
        fun http(code: Int) = retrofit2.HttpException(
            retrofit2.Response.error<Any>(code, okhttp3.ResponseBody.Companion.create(null, "")),
        )
        assertTrue(ReleaseSource.shouldFallBackToGitHub(http(404)))
        assertTrue(ReleaseSource.shouldFallBackToGitHub(java.net.UnknownServiceException("CLEARTEXT")))
        assertTrue(ReleaseSource.shouldFallBackToGitHub(java.net.UnknownHostException("x")))
        assertEquals(false, ReleaseSource.shouldFallBackToGitHub(http(401)))
        assertEquals(false, ReleaseSource.shouldFallBackToGitHub(http(403)))
        assertEquals(false, ReleaseSource.shouldFallBackToGitHub(null))
    }
}
