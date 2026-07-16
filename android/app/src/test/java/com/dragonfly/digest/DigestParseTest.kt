package com.dragonfly.digest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** The DTO parses the frozen contract leniently: nullable domains, missing fields, extra keys. */
class DigestParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String): WeeklyDigest = json.decodeFromString(body)

    @Test
    fun `parses the full happy-path payload including extra keys`() {
        val digest = parse(
            """
            {
              "week_start": "2026-07-13", "week_end": "2026-07-19",
              "narrative": "a warm paragraph",
              "domains": {
                "training":  { "totals": { "days_trained": 4, "strength_sessions": 3, "cardio_sessions": 1 } },
                "nutrition": { "days_logged": 6, "avg_calories": 1980.0, "calorie_adherence_pct": 83.0, "protein_adherence_pct": 71.0, "weight_change_kg": -0.3, "extra_key": "ignored" },
                "cooking":   { "count": 5, "distinct_recipes": 4 },
                "money":     { "income_cents": 450000, "spend_cents": -132000, "net_cents": 318000, "future_field": 1 }
              },
              "generated_at": "2026-07-19T18:00:00Z"
            }
            """.trimIndent(),
        )

        assertEquals("2026-07-13", digest.weekStart)
        assertEquals("2026-07-19", digest.weekEnd)
        assertEquals("a warm paragraph", digest.narrative)
        assertEquals(4, digest.domains.training?.totals?.daysTrained)
        assertEquals(1, digest.domains.training?.totals?.cardioSessions)
        assertEquals(6, digest.domains.nutrition?.daysLogged)
        assertEquals(83.0, digest.domains.nutrition?.calorieAdherencePct)
        assertEquals(-0.3, digest.domains.nutrition?.weightChangeKg)
        assertEquals(5, digest.domains.cooking?.count)
        assertEquals(318000, digest.domains.money?.netCents)
        assertEquals("2026-07-19T18:00:00Z", digest.generatedAt)
        assertEquals(
            listOf(DigestDomain.TRAINING, DigestDomain.NUTRITION, DigestDomain.COOKING, DigestDomain.MONEY),
            DigestFormatter.visibleDomains(digest),
        )
    }

    @Test
    fun `null narrative and null domains parse and yield no cards`() {
        val digest = parse(
            """
            {
              "week_start": "2026-07-13", "week_end": "2026-07-19",
              "narrative": null,
              "domains": { "training": null, "nutrition": null, "cooking": null, "money": null },
              "generated_at": "2026-07-19T18:00:00Z"
            }
            """.trimIndent(),
        )
        assertNull(digest.narrative)
        assertTrue(DigestFormatter.visibleDomains(digest).isEmpty())
    }

    @Test
    fun `partial payload — only some domains present, missing top-level fields default`() {
        val digest = parse(
            """
            {
              "week_start": "2026-07-13", "week_end": "2026-07-19",
              "domains": { "cooking": { "count": 5, "distinct_recipes": 4 } }
            }
            """.trimIndent(),
        )
        assertNull(digest.narrative)
        assertNull(digest.generatedAt)
        assertNull(digest.domains.training)
        assertEquals(listOf(DigestDomain.COOKING), DigestFormatter.visibleDomains(digest))
    }

    @Test
    fun `training present with missing totals object`() {
        val digest = parse("""{ "domains": { "training": {} } }""")
        assertNull(digest.domains.training?.totals)
        assertEquals(listOf(DigestDomain.TRAINING), DigestFormatter.visibleDomains(digest))
        assertEquals("0 days trained · 0+0 sessions", DigestFormatter.trainingHeadline(digest.domains.training!!))
    }

    @Test
    fun `empty object parses to an all-null digest`() {
        val digest = parse("{}")
        assertNull(digest.weekStart)
        assertTrue(DigestFormatter.visibleDomains(digest).isEmpty())
    }
}
