package com.dragonfly.digest

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The pure half of the weekly digest — headline strings, dollar formatting, the week-range label,
 * and the "which cards show" rule. Kept free of Android/OkHttp so it's trivially unit-testable
 * (the `status/StatusResolver` precedent).
 */
object DigestFormatter {

    /** Which headline cards to render: a domain shows only when its object is present (non-null). */
    fun visibleDomains(digest: WeeklyDigest): List<DigestDomain> {
        val d = digest.domains
        return buildList {
            if (d.training != null) add(DigestDomain.TRAINING)
            if (d.nutrition != null) add(DigestDomain.NUTRITION)
            if (d.cooking != null) add(DigestDomain.COOKING)
            if (d.money != null) add(DigestDomain.MONEY)
        }
    }

    /** "4 days trained · 3+1 sessions". Missing totals degrade to zeros rather than crash. */
    fun trainingHeadline(training: TrainingDigest): String {
        val t = training.totals ?: TrainingTotals()
        return "${t.daysTrained} ${daysWord(t.daysTrained)} trained · " +
            "${t.strengthSessions}+${t.cardioSessions} sessions"
    }

    /** "6 days logged · 83% cals". The adherence clause is dropped when the server omitted it. */
    fun nutritionHeadline(nutrition: NutritionDigest): String {
        val base = "${nutrition.daysLogged} ${daysWord(nutrition.daysLogged)} logged"
        val cals = nutrition.calorieAdherencePct?.let { " · ${it.roundToInt()}% cals" } ?: ""
        return base + cals
    }

    /** "5 meals · 4 recipes". */
    fun cookingHeadline(cooking: CookingDigest): String =
        "${cooking.count} ${if (cooking.count == 1) "meal" else "meals"} · " +
            "${cooking.distinctRecipes} ${if (cooking.distinctRecipes == 1) "recipe" else "recipes"}"

    /** Net as signed dollars: "+$3,180.00" / "-$1,320.00". */
    fun moneyHeadline(money: MoneyDigest): String = netDollars(money.netCents)

    /** True when the week's net is non-negative (green in the UI; red when false). */
    fun isNetPositive(money: MoneyDigest): Boolean = money.netCents >= 0

    /** Signed dollar string from a cents amount: 318000 -> "+$3,180.00", -132000 -> "-$1,320.00". */
    fun netDollars(cents: Long): String {
        val sign = if (cents < 0) "-" else "+"
        val absCents = abs(cents)
        val dollars = absCents / 100
        val remainder = absCents % 100
        return "$sign$" + String.format(Locale.US, "%,d.%02d", dollars, remainder)
    }

    /**
     * Compact week range from two ISO dates: same month -> "Jul 13–19", spanning months ->
     * "Jul 30 – Aug 5". Returns null if either date is missing or unparseable (the screen then
     * falls back to the raw strings).
     */
    fun weekRange(weekStart: String?, weekEnd: String?): String? {
        val start = parseDate(weekStart) ?: return null
        val end = parseDate(weekEnd) ?: return null
        val startMonth = start.month.short()
        return if (start.month == end.month && start.year == end.year) {
            "$startMonth ${start.dayOfMonth}–${end.dayOfMonth}"
        } else {
            "$startMonth ${start.dayOfMonth} – ${end.month.short()} ${end.dayOfMonth}"
        }
    }

    /** "updated 3h ago" from an ISO instant, or null if absent/unparseable. */
    fun updatedLabel(generatedAt: String?, now: Instant): String? {
        val instant = generatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return "updated ${relativeTime(instant, now)}"
    }

    private fun daysWord(n: Int): String = if (n == 1) "day" else "days"

    private fun parseDate(value: String?): LocalDate? =
        value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun Month.short(): String = getDisplayName(TextStyle.SHORT, Locale.US)

    /** "just now" / "5m ago" / "3h ago" / "2d ago"; a future instant clamps to "just now". */
    private fun relativeTime(from: Instant, now: Instant): String {
        val secs = Duration.between(from, now).seconds
        return when {
            secs < 45 -> "just now"
            secs < 90 -> "1m ago"
            secs < 3600 -> "${(secs + 30) / 60}m ago"
            secs < 5400 -> "1h ago"
            secs < 86_400 -> "${(secs + 1800) / 3600}h ago"
            secs < 172_800 -> "1d ago"
            else -> "${(secs + 43_200) / 86_400}d ago"
        }
    }
}
