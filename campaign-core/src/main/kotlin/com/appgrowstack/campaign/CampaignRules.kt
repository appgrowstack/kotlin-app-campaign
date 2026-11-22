package com.appgrowstack.campaign

import java.time.Instant

/** Everything one decision reads. Nothing here reads a clock, storage or the network. */
public data class CampaignDecisionInput(
    val set: CampaignSet,
    val registry: CampaignRegistry,
    val fetchedAt: Long,
    val now: Long,
    val context: CampaignContext,
    /** R2's person id. Decides the holdout side. */
    val person: String,
    val daysSinceFirstSeen: Int,
    val history: CampaignHistory,
    /** Campaigns shown since this launch. */
    val sessionShown: Int,
    val event: ObservedEvent,
    /**
     * Events heard earlier in this launch, oldest first. The engine keeps only those some campaign's
     * `not_after_in_launch` matches ([CampaignRules.remembersInLaunch]).
     */
    val launchEvents: List<ObservedEvent> = emptyList(),
    /** Seconds since this launch, for the `session_seconds` fact. */
    val sessionSeconds: Long? = null,
    /** The app's strings table: `<text_key>.<value name>` → text in the device's language. */
    val texts: (String) -> String? = { null },
    /** HTTPS images already fetched. */
    val imagesReady: Set<String> = emptySet(),
    /** Sides already decided on this device. A side, once decided, does not change. */
    val sides: Map<String, Experiment.Side> = emptyMap(),
)

public data class CampaignSelection(
    val campaign: Campaign,
    val trigger: CampaignTrigger,
    val values: Map<String, CampaignResolvedValue>,
    /**
     * The variant's id (R10 P5), or `null` for a campaign without variants. Sent as `variant` on
     * `campaign_shown`, `campaign_tapped`, `campaign_dismissed` and `campaign_action_completed`.
     */
    val variant: String? = null,
)

public data class CampaignDecision(
    /** The campaign to show (after waiting while the app is busy), or `null`. */
    val shown: CampaignSelection?,
    /** Every campaign in the set that was looked at. */
    val outcomes: Map<String, CampaignOutcome>,
    /** Measurements this decision produced (eligible, skipped, type mismatches, stale). */
    val records: List<CampaignEvent>,
    /** The server facts that were asked for, in order. */
    val serverFactsRead: List<String>,
    /** Every campaign that passed step 8, with its side. Held-out ones count as shown. */
    val eligible: List<Eligible>,
) {
    /** [variant]: the variant chosen on the shown side; `null` for a campaign without variants and for the holdout side. */
    public data class Eligible(val campaign: String, val trigger: String, val side: Experiment.Side, val variant: String? = null)
}

/** After waiting: show, or why not (C4). */
public sealed interface CampaignRecheck {
    public data class Show(val selection: CampaignSelection) : CampaignRecheck
    public data class NotShown(val outcome: CampaignOutcome, val records: List<CampaignEvent>) : CampaignRecheck
}

/** The decision, as functions: which campaign to show for an event, or why none. */
public object CampaignRules {
    /**
     * Steps 1–10 of the decision order (`docs/contracts.md` 6.5).
     *
     * @param facts reads a fact by name; `null` means "not available". Called lazily: server facts
     *   only for campaigns whose every device condition has passed, each name at most once.
     */
    public suspend fun decide(input: CampaignDecisionInput, facts: suspend (String) -> CampaignValue?): CampaignDecision {
        val set = input.set
        val version = set.version
        val outcomes = linkedMapOf<String, CampaignOutcome>()
        val records = mutableListOf<CampaignEvent>()
        val eligible = mutableListOf<CampaignDecision.Eligible>()

        val age = input.now - input.fetchedAt
        if (age > set.policy.cacheHours * 3_600_000) {
            set.campaigns.forEach { outcomes[it.id] = CampaignOutcome.Stale }
            records.add(CampaignEvent.stale(version, (age / 3_600_000).toInt()))
            return CampaignDecision(null, outcomes, records, emptyList(), emptyList())
        }

        val reader = FactReader(input, facts)
        // The product-wide holdout (R10), when the set applies it. The owner's device is always shown.
        val inGlobalHoldout = set.policy.globalHoldout && !input.context.internalDevice &&
            Experiment.globalUnit(set.project, input.person) < Experiment.GLOBAL_HOLDOUT
        val candidates = mutableListOf<Triple<Campaign, CampaignTrigger, CampaignVariant?>>()

        for (campaign in set.campaigns) {
            inactive(campaign, input)?.let { outcomes[campaign.id] = it; continue }

            val (trigger, triggerMismatch) = matchTrigger(campaign, input)
            if (triggerMismatch) records.add(CampaignEvent.rejected(campaign.id, version, CampaignRejectReason.TYPE_MISMATCH))
            if (trigger == null) { outcomes[campaign.id] = CampaignOutcome.NoTrigger; continue }

            limit(campaign, input)?.let { outcomes[campaign.id] = it; continue }

            campaign.audience?.let { audience ->
                val evaluator = Evaluator(emptyMap(), reader)
                var result = evaluator.evaluate(audience, Phase.DEVICE)
                if (result == Match.DEFERRED) result = evaluator.evaluate(audience, Phase.FULL)
                if (evaluator.mismatch) records.add(CampaignEvent.rejected(campaign.id, version, CampaignRejectReason.TYPE_MISMATCH))
                if (result != Match.YES) {
                    if (evaluator.serverUnavailable) {
                        outcomes[campaign.id] = CampaignOutcome.FactUnavailable
                        records.add(CampaignEvent.skipped(campaign.id, version, trigger.id, CampaignSkipReason.FACT_UNAVAILABLE))
                    } else {
                        outcomes[campaign.id] = CampaignOutcome.Audience
                    }
                    continue
                }
            }

            // The product-wide holdout first: not shown and not recorded as eligible, so the
            // campaign's own counts and SRM stay clean.
            if (inGlobalHoldout) { outcomes[campaign.id] = CampaignOutcome.GlobalHoldout; continue }

            // Both sides are recorded as eligible, with the chance of that side. On the shown side of a
            // campaign with variants, the variant is chosen too and the chance is the variant's;
            // `policy_version` is then the allocation's.
            val side = side(campaign, input)
            val allocation = allocationOf(campaign, set)
            val variants = campaign.variants
            val index = if (side == Experiment.Side.TREATMENT && allocation != null && variants != null) {
                Experiment.variantIndex(set.project, input.person, campaign.id, allocation.weights, input.context.internalDevice)
            } else {
                null
            }
            val variant = if (index != null && variants != null) variants[index] else null
            val propensity = when {
                input.context.internalDevice -> if (side == Experiment.Side.TREATMENT) 1.0 else 0.0
                side == Experiment.Side.HOLDOUT -> campaign.holdout
                index != null && allocation != null -> (1 - campaign.holdout) * allocation.weights[index] / allocation.weights.sum()
                else -> 1 - campaign.holdout
            }
            records.add(CampaignEvent.eligible(
                campaign.id, version, trigger.id, side, Experiment.round6(propensity),
                variant = variant?.id, policyVersion = allocation?.policyVersion ?: version,
            ))
            eligible.add(CampaignDecision.Eligible(campaign.id, trigger.id, side, variant?.id))
            if (side == Experiment.Side.HOLDOUT) {
                outcomes[campaign.id] = CampaignOutcome.HeldOut
                continue
            }
            candidates.add(Triple(campaign, trigger, variant))
        }

        val sorted = candidates.sortedWith { a, b -> compareRank(a.first, b.first) }
        val winner = sorted.firstOrNull()
            ?: return CampaignDecision(null, outcomes, records, reader.serverRead.toList(), eligible)
        for ((loser, loserTrigger, _) in sorted.drop(1)) {
            outcomes[loser.id] = CampaignOutcome.Skipped(CampaignSkipReason.LOWER_PRIORITY)
            records.add(CampaignEvent.skipped(loser.id, version, loserTrigger.id, CampaignSkipReason.LOWER_PRIORITY))
        }
        val (campaign, trigger, variant) = winner
        var shown: CampaignSelection? = null
        // Step 10 with the chosen variant's values over the campaign's.
        when (val values = resolve(campaign, input, variant)) {
            is Resolved.Values -> {
                outcomes[campaign.id] = CampaignOutcome.Show
                shown = CampaignSelection(campaign, trigger, values.values, variant?.id)
            }
            is Resolved.Skip -> {
                outcomes[campaign.id] = CampaignOutcome.Skipped(values.reason)
                records.add(CampaignEvent.skipped(campaign.id, version, trigger.id, values.reason))
            }
        }
        return CampaignDecision(shown, outcomes, records, reader.serverRead.toList(), eligible)
    }

    /**
     * Step 11': after the app stopped being busy, steps 3–5 once more against the set and counts on
     * hand at that time (`input.now` is the time after waiting). A campaign that left the set is
     * `withdrawn`.
     */
    public fun recheck(selection: CampaignSelection, input: CampaignDecisionInput): CampaignRecheck {
        val id = selection.campaign.id
        val trigger = selection.trigger.id
        val campaign = input.set.campaigns.firstOrNull { it.id == id }
            ?: return CampaignRecheck.NotShown(
                CampaignOutcome.Skipped(CampaignSkipReason.WITHDRAWN),
                listOf(CampaignEvent.skipped(id, input.set.version, trigger, CampaignSkipReason.WITHDRAWN)),
            )
        inactive(campaign, input)?.let { return CampaignRecheck.NotShown(it, emptyList()) }
        val matched = matchTrigger(campaign, input).first ?: return CampaignRecheck.NotShown(CampaignOutcome.NoTrigger, emptyList())
        limit(campaign, input)?.let { return CampaignRecheck.NotShown(it, emptyList()) }
        // The variant chosen before waiting stays; it is looked up in the set as it is now.
        val variant = campaign.variants?.firstOrNull { it.id == selection.variant }
        return when (val values = resolve(campaign, input, variant)) {
            is Resolved.Values -> CampaignRecheck.Show(CampaignSelection(campaign, matched, values.values, variant?.id))
            is Resolved.Skip -> CampaignRecheck.NotShown(
                CampaignOutcome.Skipped(values.reason),
                listOf(CampaignEvent.skipped(id, input.set.version, matched.id, values.reason)),
            )
        }
    }

    /** The app was busy longer than the trigger's `wait_seconds`. */
    public fun busy(selection: CampaignSelection, setVersion: Int): CampaignRecheck.NotShown = CampaignRecheck.NotShown(
        CampaignOutcome.Skipped(CampaignSkipReason.BUSY),
        listOf(CampaignEvent.skipped(selection.campaign.id, setVersion, selection.trigger.id, CampaignSkipReason.BUSY)),
    )

    /**
     * The campaign's allocation: the set's entry, else equal weights with the set's version. `null` for a
     * campaign without variants.
     */
    public fun allocationOf(campaign: Campaign, set: CampaignSet): CampaignAllocation? {
        val variants = campaign.variants ?: return null
        return set.allocations[campaign.id] ?: CampaignAllocation(variants.map { 1 }, set.version)
    }

    /** `priority` high first, then the newer `starts_at` (none counts as oldest), then the id. */
    public fun compareRank(lhs: Campaign, rhs: Campaign): Int {
        if (lhs.priority != rhs.priority) return rhs.priority.compareTo(lhs.priority)
        val l = lhs.startsAt ?: Long.MIN_VALUE
        val r = rhs.startsAt ?: Long.MIN_VALUE
        if (l != r) return r.compareTo(l)
        return lhs.id.compareTo(rhs.id)
    }

    /**
     * The side of a campaign's holdout: the owner's device is always shown; a side decided earlier
     * stays; otherwise `hash32("<project>:<person>:<campaign>:holdout") / 2^32 < holdout`.
     */
    private fun side(campaign: Campaign, input: CampaignDecisionInput): Experiment.Side {
        if (input.context.internalDevice) return Experiment.Side.TREATMENT
        input.sides[campaign.id]?.let { return it }
        return Experiment.assign(input.set.project, input.person, campaign.id, campaign.holdout, internal = false).side
    }

    private fun matchTrigger(campaign: Campaign, input: CampaignDecisionInput): Pair<CampaignTrigger?, Boolean> {
        val event = input.event
        var mismatch = false
        for (trigger in campaign.triggers) {
            if (trigger.event != event.name) continue
            val condition = trigger.condition
            if (condition != null) {
                val evaluator = Evaluator(event.props, null)
                val match = evaluator.evaluateNow(condition)
                mismatch = mismatch || evaluator.mismatch
                if (match != Match.YES) continue
            }
            // `app_updated` matches only when the version before the update is older than the range's `min`:
            // a person who already had what the campaign announces is not shown it.
            if (trigger.event == APP_UPDATED && !updatedAcross(campaign, input)) continue
            return trigger to mismatch
        }
        return null to mismatch
    }

    private const val APP_UPDATED = "app_updated"

    /** `from_version` of the `app_updated` event is older than the `min` of the campaign's range for this platform. */
    private fun updatedAcross(campaign: Campaign, input: CampaignDecisionInput): Boolean {
        val range = campaign.appVersion[input.context.platform] ?: return false
        val text = (input.event.props["from_version"] as? CampaignValue.StringValue)?.value ?: return false
        val from = CampaignVersion.parse(text) ?: return false
        return from < range.min
    }

    /**
     * Days since the running version was released (`version_age_days`): whole days from its `released_at`
     * in the set's `releases` for this platform. `null` when the version is not there or its rollout was halted.
     */
    public fun versionAgeDays(set: CampaignSet, platform: String, appVersion: String, now: Long): Int? {
        val version = CampaignVersion.parse(appVersion) ?: return null
        val release = set.releases[platform]?.firstOrNull { it.version == version } ?: return null
        if (release.halted) return null
        return maxOf(0L, Math.floorDiv(now - release.releasedAt, 86_400_000L)).toInt()
    }

    private fun inactive(campaign: Campaign, input: CampaignDecisionInput): CampaignOutcome? {
        val context = input.context
        if (campaign.status != "active") return CampaignOutcome.Inactive("status")
        if (context.platform !in campaign.platforms) return CampaignOutcome.Inactive("platform")
        val range = campaign.appVersion[context.platform]
        val version = CampaignVersion.parse(context.appVersion)
        if (range == null || version == null || version !in range) return CampaignOutcome.Inactive("app_version")
        if (context.channel !in campaign.channels) return CampaignOutcome.Inactive("channel")
        campaign.startsAt?.let { if (input.now < it) return CampaignOutcome.Inactive("not_started") }
        campaign.endsAt?.let { if (input.now >= it) return CampaignOutcome.Inactive("ended") }
        return null
    }

    private fun limit(campaign: Campaign, input: CampaignDecisionInput): CampaignOutcome? {
        val record = input.history.record(campaign.id)
        val limits = campaign.limits
        val now = input.now
        if (record.shownCount >= limits.maxShown) return CampaignOutcome.Limited("max_shown")
        limits.maxPerAppVersion?.let { max ->
            if ((record.shownByAppVersion[input.context.appVersion] ?: 0) >= max) return CampaignOutcome.Limited("max_per_app_version")
        }
        record.lastShownAt?.let { if (now - it < limits.minHoursBetween * 3_600_000) return CampaignOutcome.Limited("min_hours_between") }
        record.dismissedAt?.let { if (now - it < limits.afterDismissDays * 86_400_000) return CampaignOutcome.Limited("after_dismiss") }
        record.actionCompletedAt?.let { completed ->
            val days = limits.afterActionDays ?: return CampaignOutcome.Limited("after_action")
            if (now - completed < days * 86_400_000) return CampaignOutcome.Limited("after_action")
        }
        if (input.launchEvents.any { matches(limits.notAfterInLaunch, it) }) return CampaignOutcome.Limited("not_after_in_launch")
        if (input.sessionShown >= input.set.policy.maxPerSession) return CampaignOutcome.Limited("max_per_session")
        val zone = input.context.timeZone
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val shownToday = input.history.shownAt.count { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today }
        if (shownToday >= input.set.policy.maxPerDay) return CampaignOutcome.Limited("max_per_day")
        return null
    }

    private sealed interface Resolved {
        data class Values(val values: Map<String, CampaignResolvedValue>) : Resolved
        data class Skip(val reason: CampaignSkipReason) : Resolved
    }

    /**
     * Step 10: picks the language and checks images. With a [variant], its values are laid over the
     * campaign's and its `text_key` (when set) is used.
     */
    public fun resolveValues(campaign: Campaign, input: CampaignDecisionInput, variant: CampaignVariant? = null): Map<String, CampaignResolvedValue>? =
        (resolve(campaign, input, variant) as? Resolved.Values)?.values

    private fun resolve(campaign: Campaign, input: CampaignDecisionInput, variant: CampaignVariant? = null): Resolved {
        val placement = input.registry.placements[campaign.placement] ?: return Resolved.Skip(CampaignSkipReason.MISSING_LOCALE)
        val values = if (variant != null) campaign.values + variant.values else campaign.values
        val textKey = variant?.textKey ?: campaign.textKey
        val resolved = linkedMapOf<String, CampaignResolvedValue>()
        for ((name, field) in placement.fields) {
            val raw = values[name]
            when (field.type) {
                CampaignFieldType.TEXT -> {
                    val text = pickText(raw as? kotlinx.serialization.json.JsonObject, input.context.lang)
                        ?: textKey?.let { input.texts("$it.$name") }
                    if (text != null) resolved[name] = CampaignResolvedValue.Text(text)
                    else if (field.required) return Resolved.Skip(CampaignSkipReason.MISSING_LOCALE)
                }
                CampaignFieldType.IMAGE -> {
                    val image = CampaignImage.fromJson(raw) ?: continue
                    if (image is CampaignImage.Url && image.url !in input.imagesReady) return Resolved.Skip(CampaignSkipReason.IMAGE_NOT_READY)
                    resolved[name] = CampaignResolvedValue.Image(image)
                }
                CampaignFieldType.INT -> (CampaignValue.fromJson(raw) as? CampaignValue.IntValue)?.let { resolved[name] = CampaignResolvedValue.IntValue(it.value) }
                CampaignFieldType.BOOL -> (CampaignValue.fromJson(raw) as? CampaignValue.BoolValue)?.let { resolved[name] = CampaignResolvedValue.BoolValue(it.value) }
                CampaignFieldType.STRING -> (CampaignValue.fromJson(raw) as? CampaignValue.StringValue)?.let { resolved[name] = CampaignResolvedValue.StringValue(it.value) }
            }
        }
        return Resolved.Values(resolved)
    }

    /** The device's language as it is, then its first part (`zh-Hant` → `zh`). */
    internal fun pickText(texts: kotlinx.serialization.json.JsonObject?, lang: String): String? {
        if (texts == null) return null
        CampaignSetLoader.string(texts[lang])?.let { return it }
        val base = lang.substringBefore("-")
        return if (base == lang) null else CampaignSetLoader.string(texts[base])
    }

    // MARK: evaluation

    private enum class Phase { DEVICE, FULL }
    private enum class Match { YES, NO, DEFERRED }

    /** Reads each fact at most once per decision and remembers which server facts were asked for. */
    private class FactReader(private val input: CampaignDecisionInput, private val read: suspend (String) -> CampaignValue?) {
        private val cache = mutableMapOf<String, CampaignValue?>()
        val serverRead = mutableListOf<String>()

        fun isServer(name: String) = input.registry.fact(name)?.source == CampaignFactSource.SERVER

        suspend fun lookup(name: String): CampaignValue? {
            if (cache.containsKey(name)) return cache[name]
            val value = sdkFact(name) ?: run {
                if (name in CampaignRegistry.sdkFacts) return@run null
                if (isServer(name)) serverRead.add(name)
                try { read(name) } catch (_: Exception) { null }
            }
            cache[name] = value
            return value
        }

        private fun sdkFact(name: String): CampaignValue? {
            val context = input.context
            return when (name) {
                "platform" -> CampaignValue.of(context.platform)
                "app_version" -> CampaignValue.of(context.appVersion)
                "build" -> CampaignValue.of(context.build)
                "channel" -> CampaignValue.of(context.channel)
                "lang" -> CampaignValue.of(context.lang)
                "region" -> CampaignValue.of(context.region)
                "days_since_first_seen" -> CampaignValue.of(input.daysSinceFirstSeen)
                "session_seconds" -> input.sessionSeconds?.let(CampaignValue::of)
                "version_age_days" -> versionAgeDays(input.set, context.platform, context.appVersion, input.now)?.let(CampaignValue::of)
                else -> null
            }
        }
    }

    /**
     * Whether the engine keeps this event for the rest of the launch: only events some campaign's
     * `not_after_in_launch` matches, handed to the next decisions as [CampaignDecisionInput.launchEvents].
     */
    public fun remembersInLaunch(set: CampaignSet, event: ObservedEvent): Boolean =
        set.campaigns.any { matches(it.limits.notAfterInLaunch, event) }

    /** The event has one of the names, and its props pass that `where`. A type mismatch does not match (and is not recorded). */
    internal fun matches(forms: List<CampaignLaunchEvent>, event: ObservedEvent): Boolean = forms.any { form ->
        form.event == event.name && (form.condition == null || Evaluator(event.props, null).evaluateNow(form.condition) == Match.YES)
    }

    /** Evaluates left to right; `all` stops at the first false, `any` at the first true. */
    private class Evaluator(private val props: Map<String, CampaignValue>, private val reader: FactReader?) {
        var mismatch = false
        var serverUnavailable = false

        /** Props only: nothing to wait for. */
        fun evaluateNow(condition: CampaignCondition): Match = when (condition) {
            is CampaignCondition.All -> {
                var result = Match.YES
                for (child in condition.children) if (evaluateNow(child) == Match.NO) { result = Match.NO; break }
                result
            }
            is CampaignCondition.AnyOf -> {
                var result = Match.NO
                for (child in condition.children) if (evaluateNow(child) == Match.YES) { result = Match.YES; break }
                result
            }
            is CampaignCondition.Leaf -> when (condition.subject) {
                is CampaignCondition.Subject.Prop -> compare(props[condition.subject.name], condition.operator)
                is CampaignCondition.Subject.Fact -> Match.NO
            }
        }

        suspend fun evaluate(condition: CampaignCondition, phase: Phase): Match {
            when (condition) {
                is CampaignCondition.All -> {
                    var deferred = false
                    for (child in condition.children) {
                        when (evaluate(child, phase)) {
                            Match.NO -> return Match.NO
                            Match.DEFERRED -> deferred = true
                            Match.YES -> Unit
                        }
                    }
                    return if (deferred) Match.DEFERRED else Match.YES
                }
                is CampaignCondition.AnyOf -> {
                    var deferred = false
                    for (child in condition.children) {
                        when (evaluate(child, phase)) {
                            Match.YES -> return Match.YES
                            Match.DEFERRED -> deferred = true
                            Match.NO -> Unit
                        }
                    }
                    return if (deferred) Match.DEFERRED else Match.NO
                }
                is CampaignCondition.Leaf -> {
                    val subject = condition.subject
                    if (subject is CampaignCondition.Subject.Prop) return compare(props[subject.name], condition.operator)
                    val reader = reader ?: return Match.NO
                    val isServer = reader.isServer(subject.name)
                    if (isServer && phase == Phase.DEVICE) return Match.DEFERRED
                    val value = reader.lookup(subject.name)
                    val match = compare(value, condition.operator)
                    if (isServer && value == null && match == Match.NO) serverUnavailable = true
                    return match
                }
            }
        }

        private fun compare(value: CampaignValue?, operator: CampaignCondition.Operator): Match {
            val result = operator.matches(value)
            if (result == null) {
                mismatch = true
                return Match.NO
            }
            return if (result) Match.YES else Match.NO
        }
    }
}
