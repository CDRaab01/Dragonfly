package com.dragonfly.digest

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DigestFormatterTest {

    // --- which cards show ---

    @Test
    fun `visible domains lists only non-null domains in render order`() {
        val digest = WeeklyDigest(
            domains = DigestDomains(
                training = TrainingDigest(TrainingTotals()),
                nutrition = null,
                cooking = CookingDigest(),
                money = null,
            ),
        )
        assertEquals(listOf(DigestDomain.TRAINING, DigestDomain.COOKING), DigestFormatter.visibleDomains(digest))
    }

    @Test
    fun `visible domains is empty when every app was unreachable`() {
        assertTrue(DigestFormatter.visibleDomains(WeeklyDigest()).isEmpty())
    }

    @Test
    fun `visible domains keeps a domain that is present but empty`() {
        // training present with null totals is still a rendered (empty) card.
        val digest = WeeklyDigest(domains = DigestDomains(training = TrainingDigest(totals = null)))
        assertEquals(listOf(DigestDomain.TRAINING), DigestFormatter.visibleDomains(digest))
    }

    // --- headlines ---

    @Test
    fun `training headline`() {
        val t = TrainingDigest(TrainingTotals(daysTrained = 4, strengthSessions = 3, cardioSessions = 1))
        assertEquals("4 days trained · 3+1 sessions", DigestFormatter.trainingHeadline(t))
    }

    @Test
    fun `training headline degrades to zeros when totals missing`() {
        assertEquals("0 days trained · 0+0 sessions", DigestFormatter.trainingHeadline(TrainingDigest(totals = null)))
    }

    @Test
    fun `training headline singular day`() {
        val t = TrainingDigest(TrainingTotals(daysTrained = 1, strengthSessions = 1, cardioSessions = 0))
        assertEquals("1 day trained · 1+0 sessions", DigestFormatter.trainingHeadline(t))
    }

    @Test
    fun `nutrition headline rounds the calorie percent`() {
        val n = NutritionDigest(daysLogged = 6, calorieAdherencePct = 83.4)
        assertEquals("6 days logged · 83% cals", DigestFormatter.nutritionHeadline(n))
    }

    @Test
    fun `nutrition headline drops the cals clause when the server omitted it`() {
        assertEquals("6 days logged", DigestFormatter.nutritionHeadline(NutritionDigest(daysLogged = 6, calorieAdherencePct = null)))
    }

    @Test
    fun `cooking headline pluralizes`() {
        assertEquals("5 meals · 4 recipes", DigestFormatter.cookingHeadline(CookingDigest(count = 5, distinctRecipes = 4)))
        assertEquals("1 meal · 1 recipe", DigestFormatter.cookingHeadline(CookingDigest(count = 1, distinctRecipes = 1)))
    }

    // --- money / dollar formatting ---

    @Test
    fun `net dollars formats positive with grouping and sign`() {
        assertEquals("+$3,180.00", DigestFormatter.netDollars(318000))
    }

    @Test
    fun `net dollars formats negative with grouping and sign`() {
        assertEquals("-$1,320.00", DigestFormatter.netDollars(-132000))
    }

    @Test
    fun `net dollars zero is positive with cents`() {
        assertEquals("+$0.00", DigestFormatter.netDollars(0))
        assertEquals("+$0.07", DigestFormatter.netDollars(7))
    }

    @Test
    fun `is net positive`() {
        assertTrue(DigestFormatter.isNetPositive(MoneyDigest(netCents = 318000)))
        assertTrue(DigestFormatter.isNetPositive(MoneyDigest(netCents = 0)))
        assertTrue(!DigestFormatter.isNetPositive(MoneyDigest(netCents = -1)))
    }

    // --- week range ---

    @Test
    fun `week range within a month`() {
        assertEquals("Jul 13–19", DigestFormatter.weekRange("2026-07-13", "2026-07-19"))
    }

    @Test
    fun `week range spanning months`() {
        assertEquals("Jul 30 – Aug 5", DigestFormatter.weekRange("2026-07-30", "2026-08-05"))
    }

    @Test
    fun `week range is null when a date is missing or unparseable`() {
        assertNull(DigestFormatter.weekRange(null, "2026-07-19"))
        assertNull(DigestFormatter.weekRange("2026-07-13", null))
        assertNull(DigestFormatter.weekRange("not-a-date", "2026-07-19"))
    }

    // --- updated label ---

    @Test
    fun `updated label is relative`() {
        val now = Instant.parse("2026-07-19T21:00:00Z")
        assertEquals("updated 3h ago", DigestFormatter.updatedLabel("2026-07-19T18:00:00Z", now))
    }

    @Test
    fun `updated label is null when absent or unparseable`() {
        val now = Instant.parse("2026-07-19T21:00:00Z")
        assertNull(DigestFormatter.updatedLabel(null, now))
        assertNull(DigestFormatter.updatedLabel("nope", now))
    }
}
