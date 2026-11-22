package com.appgrowstack.campaign

/** A campaign the app should present now, in its own placement. */
public data class CampaignPresentation(
    val id: String,
    val campaign: String,
    val setVersion: Int,
    val trigger: String,
    val placement: String,
    val values: Map<String, CampaignResolvedValue>,
    val event: ObservedEvent,
    val onTap: CampaignTap,
    /** The campaign's variant being shown (R10 P5), or `null` for a campaign without variants. */
    val variant: String? = null,
)

/** The presentation as one placement's values, with the two ways out. */
public class CampaignOffer<V>(
    public val presentation: CampaignPresentation,
    public val values: V,
    /** The primary button: closes the placement, then runs the action or opens the link. */
    public val tap: () -> Unit,
    /** Any way of closing without the primary button. */
    public val dismiss: (CampaignDismissal) -> Unit,
    /** A `survey` answer (a [CampaignSurvey.Values.choices] id). Records `survey_answered`, then closes like [tap]. */
    public val answer: (String) -> Unit,
)
