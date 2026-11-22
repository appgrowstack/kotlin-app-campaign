package com.appgrowstack.campaign

/** Why a set or a campaign could not be used (`RejectReason`). */
public enum class CampaignRejectReason(public val code: String) {
    PARSE_ERROR("parse_error"),
    SCHEMA_TOO_NEW("schema_too_new"),
    INVALID("invalid"),
    UNKNOWN_PLACEMENT("unknown_placement"),
    UNKNOWN_ACTION("unknown_action"),
    UNKNOWN_ASSET("unknown_asset"),
    UNKNOWN_FACT("unknown_fact"),
    UNKNOWN_OPERATOR("unknown_operator"),
    UNKNOWN_TARGET("unknown_target"),
    MISSING_VALUE("missing_value"),
    UNKNOWN_VALUE("unknown_value"),
    TYPE_MISMATCH("type_mismatch"),
}

/** Why a campaign that passed was not shown (`SkipReason`). */
public enum class CampaignSkipReason(public val code: String) {
    BUSY("busy"),
    LOWER_PRIORITY("lower_priority"),
    MISSING_LOCALE("missing_locale"),
    IMAGE_NOT_READY("image_not_ready"),
    FACT_UNAVAILABLE("fact_unavailable"),
    WITHDRAWN("withdrawn"),
}

public enum class CampaignDismissal(public val code: String) {
    SECONDARY("secondary"), CLOSE_BUTTON("close_button"), SWIPE_DOWN("swipe_down"), OUTSIDE("outside")
}

/**
 * A measurement for the app's analytics (`inapp/events.schema.json`). The SDK sends nothing itself:
 * every event goes to the engine's `record`.
 */
public data class CampaignEvent(val name: String, val params: Map<String, CampaignValue>) {
    public companion object {
        private fun base(campaign: String, setVersion: Int, trigger: String) = mapOf(
            "campaign" to CampaignValue.of(campaign),
            "set_version" to CampaignValue.of(setVersion),
            "trigger" to CampaignValue.of(trigger),
        )

        /** [propensity] is the chance of [side]; `policy_version` is the set's version (R10). */
        public fun eligible(campaign: String, setVersion: Int, trigger: String, side: Experiment.Side, propensity: Double): CampaignEvent =
            CampaignEvent("campaign_eligible", base(campaign, setVersion, trigger) + mapOf(
                "side" to CampaignValue.of(side.code),
                "propensity" to CampaignValue.of(propensity),
                "policy_version" to CampaignValue.of(setVersion),
            ))

        public fun skipped(campaign: String, setVersion: Int, trigger: String, reason: CampaignSkipReason): CampaignEvent =
            CampaignEvent("campaign_skipped", base(campaign, setVersion, trigger) + ("reason" to CampaignValue.of(reason.code)))

        public fun rejected(campaign: String, setVersion: Int, reason: CampaignRejectReason): CampaignEvent =
            CampaignEvent("campaign_rejected", mapOf(
                "campaign" to CampaignValue.of(campaign),
                "set_version" to CampaignValue.of(setVersion),
                "reason" to CampaignValue.of(reason.code),
            ))

        public fun stale(setVersion: Int, hours: Int): CampaignEvent =
            CampaignEvent("campaigns_stale", mapOf("set_version" to CampaignValue.of(setVersion), "hours" to CampaignValue.of(hours)))

        public fun shown(campaign: String, setVersion: Int, trigger: String, placement: String): CampaignEvent =
            CampaignEvent("campaign_shown", base(campaign, setVersion, trigger) + ("placement" to CampaignValue.of(placement)))

        public fun tapped(campaign: String, setVersion: Int, trigger: String, target: String): CampaignEvent =
            CampaignEvent("campaign_tapped", base(campaign, setVersion, trigger) + ("target" to CampaignValue.of(target)))

        public fun dismissed(campaign: String, setVersion: Int, trigger: String, via: CampaignDismissal): CampaignEvent =
            CampaignEvent("campaign_dismissed", base(campaign, setVersion, trigger) + ("via" to CampaignValue.of(via.code)))

        public fun actionCompleted(campaign: String, setVersion: Int, trigger: String, action: String, result: CampaignActionResult): CampaignEvent =
            CampaignEvent("campaign_action_completed", base(campaign, setVersion, trigger) + mapOf(
                "action" to CampaignValue.of(action), "result" to CampaignValue.of(result.code),
            ))

        public fun appUpdated(from: String, to: String): CampaignEvent =
            CampaignEvent("app_updated", mapOf("from_version" to CampaignValue.of(from), "to_version" to CampaignValue.of(to)))
    }
}

/** The outcome for one campaign. [code] is the string the shared cases use. */
public sealed interface CampaignOutcome {
    public val code: String

    public data object Stale : CampaignOutcome { override val code: String = "stale" }
    public data class Rejected(val reason: CampaignRejectReason) : CampaignOutcome { override val code: String get() = "rejected:${reason.code}" }
    public data class Inactive(val what: String) : CampaignOutcome { override val code: String get() = "inactive:$what" }
    public data object NoTrigger : CampaignOutcome { override val code: String = "no_trigger" }
    public data class Limited(val what: String) : CampaignOutcome { override val code: String get() = "limited:$what" }
    public data object Audience : CampaignOutcome { override val code: String = "audience" }
    public data object FactUnavailable : CampaignOutcome { override val code: String = "fact_unavailable" }
    /** The person is in the product-wide holdout (R10) and is shown no campaign. */
    public data object GlobalHoldout : CampaignOutcome { override val code: String = "global_holdout" }
    public data object HeldOut : CampaignOutcome { override val code: String = "held_out" }
    public data class Skipped(val reason: CampaignSkipReason) : CampaignOutcome { override val code: String get() = reason.code }
    public data object Show : CampaignOutcome { override val code: String = "show" }
}
