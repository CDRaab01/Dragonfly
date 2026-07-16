package com.dragonfly.digest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The suite's weekly recap, served by dragonfly-id at `GET {base}/digest/weekly`.
 *
 * Everything is optional. Any `domains.*` entry is null when that app was unreachable at generation
 * time; `narrative` is null when the LM was down. Parsed leniently (`ignoreUnknownKeys`, nullable
 * fields with defaults) — the money/nutrition objects may carry keys this client doesn't render.
 */
@Serializable
data class WeeklyDigest(
    @SerialName("week_start") val weekStart: String? = null,
    @SerialName("week_end") val weekEnd: String? = null,
    val narrative: String? = null,
    val domains: DigestDomains = DigestDomains(),
    @SerialName("generated_at") val generatedAt: String? = null,
)

@Serializable
data class DigestDomains(
    val training: TrainingDigest? = null,
    val nutrition: NutritionDigest? = null,
    val cooking: CookingDigest? = null,
    val money: MoneyDigest? = null,
)

@Serializable
data class TrainingDigest(
    val totals: TrainingTotals? = null,
)

@Serializable
data class TrainingTotals(
    @SerialName("days_trained") val daysTrained: Int = 0,
    @SerialName("strength_sessions") val strengthSessions: Int = 0,
    @SerialName("cardio_sessions") val cardioSessions: Int = 0,
)

@Serializable
data class NutritionDigest(
    @SerialName("days_logged") val daysLogged: Int = 0,
    @SerialName("avg_calories") val avgCalories: Double? = null,
    @SerialName("calorie_adherence_pct") val calorieAdherencePct: Double? = null,
    @SerialName("protein_adherence_pct") val proteinAdherencePct: Double? = null,
    @SerialName("weight_change_kg") val weightChangeKg: Double? = null,
)

@Serializable
data class CookingDigest(
    val count: Int = 0,
    @SerialName("distinct_recipes") val distinctRecipes: Int = 0,
)

@Serializable
data class MoneyDigest(
    @SerialName("income_cents") val incomeCents: Long = 0,
    @SerialName("spend_cents") val spendCents: Long = 0,
    @SerialName("net_cents") val netCents: Long = 0,
)

/** The four headline domains, in the order the screen renders them. */
enum class DigestDomain { TRAINING, NUTRITION, COOKING, MONEY }

/** Outcome of a `/digest/weekly` fetch — mirrors the frozen 200/404/401 contract. */
sealed interface DigestResult {
    data class Success(val digest: WeeklyDigest) : DigestResult

    /** 404 — no digest has been generated for the current week yet. */
    data object NotYet : DigestResult

    /** 401 — the key is wrong or unset. */
    data object NeedsKey : DigestResult

    /** Network failure, bad URL, or an unexpected status. */
    data class Error(val message: String) : DigestResult
}
