package com.appgrowstack.campaign

/**
 * What a launch starts from: the stored state with the app's own first-launch date and this launch's
 * app version, and the version the app was updated from (when this is the first launch of a newer one).
 */
internal object CampaignLaunch {
    class Start(
        val state: CampaignStoredState,
        /** The app version of the previous launch, when this launch runs a newer one. */
        val updatedFrom: String?,
    )

    fun start(stored: CampaignStoredState?, context: CampaignContext, now: Long): Start {
        var next = stored ?: CampaignStoredState(firstSeenAt = now)
        // The app's own first-launch date counts when it is earlier than the one stored here.
        context.firstSeenAt?.let { given -> if (given < next.firstSeenAt) next = next.copy(firstSeenAt = given) }
        val previous = next.lastAppVersion?.let(CampaignVersion::parse)
        val current = CampaignVersion.parse(context.appVersion)
        val updatedFrom = if (stored != null && previous != null && current != null && current > previous) next.lastAppVersion!! else null
        return Start(next.copy(lastAppVersion = context.appVersion), updatedFrom)
    }
}
