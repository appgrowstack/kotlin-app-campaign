package com.appgrowstack.campaign

import kotlinx.serialization.Serializable

/**
 * Counts for one campaign. `shownCount`, `lastShownAt` and `shownByAppVersion` also count the turns
 * on the held-out side, so the two sides are limited alike. Times are epoch milliseconds.
 */
@Serializable
public data class CampaignRecord(
    val shownCount: Int = 0,
    val lastShownAt: Long? = null,
    /** App version → times. For `max_per_app_version`. */
    val shownByAppVersion: Map<String, Int> = emptyMap(),
    val dismissedAt: Long? = null,
    val actionCompletedAt: Long? = null,
)

/** What limits read: every campaign shown (for `max_per_day`) and the counts per campaign. */
@Serializable
public data class CampaignHistory(
    /** When anything was shown (the last two days are enough). */
    val shownAt: List<Long> = emptyList(),
    val campaigns: Map<String, CampaignRecord> = emptyMap(),
) {
    public fun record(id: String): CampaignRecord = campaigns[id] ?: CampaignRecord()

    public fun updating(id: String, change: (CampaignRecord) -> CampaignRecord): CampaignHistory =
        copy(campaigns = campaigns + (id to change(record(id))))

    /** A turn on either side: counts toward `max_shown`, `min_hours_between` and `max_per_app_version`. */
    public fun counting(id: String, at: Long, appVersion: String): CampaignHistory = updating(id) {
        it.copy(
            shownCount = it.shownCount + 1,
            lastShownAt = at,
            shownByAppVersion = it.shownByAppVersion + (appVersion to (it.shownByAppVersion[appVersion] ?: 0) + 1),
        )
    }
}
