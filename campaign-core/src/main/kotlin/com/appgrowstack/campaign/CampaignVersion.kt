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
    }
}

/** `">=3.3.0"` or `">=3.3.0 <4.0.0"`, and nothing else. */
public data class VersionRange(val lower: CampaignVersion, val upper: CampaignVersion?) {
    public operator fun contains(version: CampaignVersion): Boolean =
        version >= lower && (upper == null || version < upper)

    public companion object {
        public fun parse(text: String): VersionRange? {
            val parts = text.split(" ")
            if (parts.size !in 1..2 || !parts[0].startsWith(">=")) return null
            val lower = strict(parts[0].removePrefix(">=")) ?: return null
            if (parts.size == 1) return VersionRange(lower, null)
            if (!parts[1].startsWith("<")) return null
            val upper = strict(parts[1].removePrefix("<")) ?: return null
            return VersionRange(lower, upper)
        }

        private fun strict(text: String): CampaignVersion? =
            if (text.split(".").size == 3) CampaignVersion.parse(text) else null
    }
}
