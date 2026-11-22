package com.appgrowstack.campaign

/** A version as up to three numbers. Missing parts count as zero (`3.10` is `3.10.0`). */
public data class CampaignVersion(val major: Int, val minor: Int = 0, val patch: Int = 0) : Comparable<CampaignVersion> {
    override fun compareTo(other: CampaignVersion): Int =
        compareValuesBy(this, other, CampaignVersion::major, CampaignVersion::minor, CampaignVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    public companion object {
        public fun parse(text: String): CampaignVersion? {
            val parts = text.split(".")
            if (parts.size !in 1..4) return null
            val numbers = parts.take(3).map { part ->
                if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
                part.toIntOrNull() ?: return null
            }.toMutableList()
            while (numbers.size < 3) numbers.add(0)
            return CampaignVersion(numbers[0], numbers[1], numbers[2])
        }

        /** A version as a set writes it: two or three groups of digits (`3.3`, `3.3.0`). */
        public fun parseAppVersion(text: String): CampaignVersion? =
            if (text.split(".").size in 2..3) parse(text) else null
    }
}

/**
 * One OS's range of a set campaign's `app_version`, resolved by core from what the campaign `requires`:
 * the lowest version that has it, minus the versions that lack it. The device only checks its version.
 */
public data class ResolvedVersionRange(val min: CampaignVersion, val exclude: List<CampaignVersion>) {
    public operator fun contains(version: CampaignVersion): Boolean =
        version >= min && exclude.none { it == version }
}
