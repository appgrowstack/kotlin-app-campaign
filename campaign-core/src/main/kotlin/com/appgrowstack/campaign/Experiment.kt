package com.appgrowstack.campaign

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.round
import kotlin.math.sqrt

/**
 * R3: the holdout assignment, the outcome window and the comparison. The same formulas as the
 * server (`packages/experiment`) and the Swift SDK; `schema/cases/experiment` holds the values all
 * of them must give.
 */
public object Experiment {
    /** Which side of a campaign's holdout a person is on. Kept as its [code] (`treatment`, `holdout`). */
    @Serializable
    public enum class Side(public val code: String) {
        @SerialName("treatment") TREATMENT("treatment"),
        @SerialName("holdout") HOLDOUT("holdout"),
    }

    public data class Assignment(val unit: Double, val side: Side)

    /** FNV-1a over UTF-16 code units, then murmur3's final mix. Unsigned 32 bits. */
    public fun hash32(input: String): Long {
        var hash = 0x811c9dc5.toInt()
        for (unit in input) {
            hash = hash xor unit.code
            hash *= 0x01000193
        }
        hash = hash xor (hash ushr 16)
        hash *= 0x85ebca6b.toInt()
        hash = hash xor (hash ushr 13)
        hash *= 0xc2b2ae35.toInt()
        hash = hash xor (hash ushr 16)
        return hash.toLong() and 0xffffffffL
    }

    public fun holdoutKey(project: String, person: String, campaign: String): String =
        "$project:$person:$campaign:holdout"

    /** `unit < holdout` is the held-out side. An internal device is always on the treatment side. */
    public fun assign(project: String, person: String, campaign: String, holdout: Double, internal: Boolean): Assignment {
        val unit = hash32(holdoutKey(project, person, campaign)) / 4_294_967_296.0
        val side = if (!internal && unit < holdout) Side.HOLDOUT else Side.TREATMENT
        return Assignment(unit, side)
    }

    public fun variantKey(project: String, person: String, campaign: String): String =
        "$project:$person:$campaign:variant"

    /**
     * The variant of a campaign with variants (R10 P5), for a person on the shown side: the first index
     * `i` with `weights[i] > 0` and `h × Σweights < Σweights[0..i] × 2^32`, where
     * `h = hash32("<project>:<person>:<campaign>:variant")`. An internal device gets the first variant
     * whose weight is above 0.
     */
    public fun variantIndex(project: String, person: String, campaign: String, weights: List<Int>, internal: Boolean): Int {
        if (internal) return weights.indexOfFirst { it > 0 }.coerceAtLeast(0)
        return weightedIndex(hash32(variantKey(project, person, campaign)), weights)
    }

    /** Integer math, so the server and both SDKs pick the same index. A weight of 0 is never picked. */
    internal fun weightedIndex(h: Long, weights: List<Int>): Int {
        val total = weights.sum().toLong()
        var cumulative = 0L
        for ((index, weight) in weights.withIndex()) {
            cumulative += weight
            if (weight > 0 && h * total < (cumulative shl 32)) return index
        }
        return weights.indexOfLast { it > 0 }.coerceAtLeast(0)
    }

    /** The share of people in the product-wide holdout (R10). */
    public const val GLOBAL_HOLDOUT: Double = 0.05

    /** `hash32("<project>:<person>:global_holdout") / 2^32`, in `[0, 1)`. */
    public fun globalUnit(project: String, person: String): Double =
        hash32("$project:$person:global_holdout") / 4_294_967_296.0

    public data class Reach(val counted: Boolean, val reached: Boolean)

    /**
     * Whether a goal happened in `[firstEligibleAt, firstEligibleAt + withinHours)`. A window that has
     * not closed yet (`now` before its end) is not counted.
     */
    public fun reach(firstEligibleAt: Long, withinHours: Double, goalAt: List<Long>, now: Long): Reach {
        val end = firstEligibleAt + withinHours * 3_600_000
        return Reach(
            counted = now >= end,
            reached = goalAt.any { it >= firstEligibleAt && it < end },
        )
    }

    public data class Arm(val eligible: Int, val reached: Int)

    public data class Comparison(
        val compared: Boolean,
        val treatmentRate: Double?,
        val holdoutRate: Double?,
        val diff: Double?,
        val low: Double?,
        val high: Double?,
    )

    /** `diff ± 1.96 × sqrt(p_t(1−p_t)/n_t + p_h(1−p_h)/n_h)`, or too few below `minPerArm`. Rounded to 6 places. */
    public fun compare(treatment: Arm, holdout: Arm, minPerArm: Int = 30): Comparison {
        fun rate(arm: Arm) = if (arm.eligible == 0) null else arm.reached.toDouble() / arm.eligible
        val pt = rate(treatment)
        val ph = rate(holdout)
        if (pt == null || ph == null || treatment.eligible < minPerArm || holdout.eligible < minPerArm) {
            return Comparison(false, pt?.let(::round6), ph?.let(::round6), null, null, null)
        }
        val diff = pt - ph
        val margin = 1.96 * sqrt(pt * (1 - pt) / treatment.eligible + ph * (1 - ph) / holdout.eligible)
        return Comparison(true, round6(pt), round6(ph), round6(diff), round6(diff - margin), round6(diff + margin))
    }

    public fun round6(value: Double): Double {
        val rounded = round(value * 1_000_000) / 1_000_000
        return if (rounded == 0.0) 0.0 else rounded
    }
}
