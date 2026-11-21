package com.appgrowstack.campaign

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

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
)

/** The presentation as one placement's values, with the two ways out. */
public class CampaignOffer<V>(
    public val presentation: CampaignPresentation,
    public val values: V,
    /** The primary button: closes the placement, then runs the action or opens the link. */
    public val tap: () -> Unit,
    /** Any way of closing without the primary button. */
    public val dismiss: (CampaignDismissal) -> Unit,
)

/**
 * Fetches the set, hears events, decides, waits until the screen is free, and hands the app a
 * presentation. Everything it measures goes to [record]; it never sends anything itself.
 *
 * The app watches [presenting] and shows its own placement while it is set; [offer] gives the
 * values as the placement's type. A `system` placement (`review_ask`) is never presented: its
 * action runs at once.
 *
 * @param projectKey the project's public key (`pk_…`). Not a secret.
 * @param person R2's person id (the first install id; `identify` may return another). Decides the holdout side.
 * @param facts cheap device facts first; conditions are read in the order they are written.
 * @param texts the app's strings for `<text_key>.<value name>` in the device's language; `null` when there is none.
 * @param record receives every `campaign_*` measurement and `app_updated`, for the app's analytics.
 * @param isBusy `true` while the app has another full screen up. Campaigns wait for it, up to the trigger's `wait_seconds`.
 */
public class CampaignEngine(
    projectKey: String,
    private val context: CampaignContext,
    private val person: () -> String,
    placements: List<CampaignPlacement<*>>,
    facts: List<CampaignFact> = emptyList(),
    actions: List<CampaignAction> = emptyList(),
    assets: Set<String> = emptySet(),
    private val storage: CampaignStorage,
    private val record: (CampaignEvent) -> Unit,
    private val fetcher: CampaignSetFetcher = HttpCampaignSetFetcher(projectKey),
    private val texts: (String) -> String? = { null },
    public var isBusy: () -> Boolean = { false },
    private val link: (String) -> Unit = {},
    private val clock: CampaignClock = SystemCampaignClock,
    private val images: CampaignImagePrefetcher = CampaignImagePrefetcher.Http,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val placements = placements.associateBy { it.name }
    private val facts = facts.associateBy { it.name }
    private val actions = actions.associateBy { it.name }

    public val registry: CampaignRegistry = CampaignRegistry(
        placements = placements.associate { it.name to it.spec },
        actions = actions.associate { it.name to it.arguments },
        facts = facts.associate { it.name to CampaignRegistry.FactSpec(it.type, it.source) },
        assets = assets,
    )

    private val presentingFlow = MutableStateFlow<CampaignPresentation?>(null)

    /** The campaign to show now. The app presents its own placement while this is set. */
    public val presenting: StateFlow<CampaignPresentation?> = presentingFlow.asStateFlow()

    /** The set on hand. */
    public var set: CampaignSet? = null
        private set

    private val mutex = Mutex()
    private var state: CampaignStoredState? = null
    private var sessionShown = 0
    private val launchedAt = clock.now()
    private val imagesReady = mutableSetOf<String>()
    private var pendingUpdate: ObservedEvent? = null
    private var waiting: Waiting? = null

    private class Waiting(val campaign: Campaign, val trigger: String)

    // MARK: fetching

    /** Fetches the set. Call at launch and on return to the foreground. Failures keep the set on hand. */
    public suspend fun refresh() {
        prepare()
        val fetched = mutex.withLock {
            val current = state ?: return
            val result = try {
                fetcher.fetch(if (set == null) null else current.etag)
            } catch (_: Exception) {
                return@withLock null
            }
            var next = current
            var loaded: CampaignSet? = null
            when (result) {
                CampaignFetchResult.NotModified -> if (set != null) next = next.copy(fetchedAt = clock.now())
                is CampaignFetchResult.Fetched -> when (val read = CampaignSetLoader.load(result.body, registry)) {
                    is CampaignSetLoader.Result.Unusable -> next = report(CampaignEvent.rejected("_set", read.version, read.reason), next)
                    is CampaignSetLoader.Result.Loaded -> {
                        read.rejections.forEach { next = report(CampaignEvent.rejected(it.campaign, read.set.version, it.reason), next) }
                        set = read.set
                        loaded = read.set
                        next = next.copy(etag = result.etag, fetchedAt = clock.now(), staleReportedVersion = null)
                        storage.saveSet(result.body)
                    }
                }
            }
            state = next
            storage.saveState(next)
            loaded
        }
        fetched?.let { prefetchImages(it) }
        pendingUpdate?.let { update ->
            if (set != null && state?.fetchedAt != null) {
                pendingUpdate = null
                receive(update)
            }
        }
    }

    private suspend fun prepare() {
        mutex.withLock {
            if (state != null) return
            val stored = storage.loadState()
            storage.loadSet()?.let { text ->
                (CampaignSetLoader.load(text, registry) as? CampaignSetLoader.Result.Loaded)?.let { set = it.set }
            }
            var next = stored ?: CampaignStoredState(firstSeenAt = clock.now())
            val previous = next.lastAppVersion?.let(CampaignVersion::parse)
            val current = CampaignVersion.parse(context.appVersion)
            if (stored != null && previous != null && current != null && current > previous) {
                val from = next.lastAppVersion!!
                record(CampaignEvent.appUpdated(from, context.appVersion))
                pendingUpdate = ObservedEvent("app_updated", mapOf(
                    "from_version" to CampaignValue.of(from), "to_version" to CampaignValue.of(context.appVersion),
                ))
            }
            next = next.copy(lastAppVersion = context.appVersion)
            state = next
            storage.saveState(next)
        }
        set?.let { prefetchImages(it) }
    }

    private suspend fun prefetchImages(set: CampaignSet) {
        for (campaign in set.campaigns) {
            for (value in campaign.values.values) {
                val image = CampaignImage.fromJson(value) as? CampaignImage.Url ?: continue
                if (image.url in imagesReady) continue
                if (images.prefetch(image.url)) imagesReady.add(image.url)
            }
        }
    }

    // MARK: events

    /** Returns at once. The decision runs in the background and never affects the caller. */
    public fun observe(name: String, props: Map<String, CampaignValue> = emptyMap()) {
        val event = ObservedEvent(name, props)
        scope.launch { receive(event) }
    }

    /** The same as [observe], awaited: returns after the campaign was shown or skipped. */
    public suspend fun receive(event: ObservedEvent) {
        if (event.name.startsWith("campaign")) return
        prepare()
        val selection = mutex.withLock {
            val current = state ?: return
            val set = set ?: return
            val fetchedAt = current.fetchedAt ?: return
            val decision = CampaignRules.decide(input(set, current, fetchedAt, event)) { name ->
                facts[name]?.let { fact -> runCatching { fact.read() }.getOrNull() }
            }
            var next = current
            for (measured in decision.records) {
                if (measured.name == "campaigns_stale") {
                    if (next.staleReportedVersion == set.version) continue
                    next = next.copy(staleReportedVersion = set.version)
                    record(measured)
                } else {
                    next = report(measured, next)
                }
            }
            val now = clock.now()
            for (held in decision.heldOut) next = next.copy(history = next.history.counting(held, now, context.appVersion))
            state = next
            storage.saveState(next)
            decision.shown
        } ?: return
        waitAndShow(selection, event)
    }

    private fun input(set: CampaignSet, state: CampaignStoredState, fetchedAt: Long, event: ObservedEvent): CampaignDecisionInput {
        val now = clock.now()
        return CampaignDecisionInput(
            set = set, registry = registry, fetchedAt = fetchedAt, now = now, context = context, person = person(),
            daysSinceFirstSeen = days(state.firstSeenAt, now), history = state.history, sessionShown = sessionShown,
            event = event, sessionSeconds = (now - launchedAt) / 1000, texts = texts, imagesReady = imagesReady.toSet(),
        )
    }

    /** Sends `campaign_rejected` once per campaign, set version and reason; everything else as is. */
    private fun report(event: CampaignEvent, state: CampaignStoredState): CampaignStoredState {
        if (event.name != "campaign_rejected") {
            record(event)
            return state
        }
        val key = listOf("campaign", "set_version", "reason").joinToString("|") { (event.params[it]?.toJson()?.content).orEmpty() }
        if (key in state.reportedRejections) return state
        record(event)
        return state.copy(reportedRejections = state.reportedRejections + key)
    }

    private suspend fun waitAndShow(selection: CampaignSelection, event: ObservedEvent) {
        val version = set?.version ?: return
        val campaign = selection.campaign
        val mine = Waiting(campaign, selection.trigger.id)
        mutex.withLock {
            waiting?.let { current ->
                if (CampaignRules.compareRank(campaign, current.campaign) < 0) {
                    record(CampaignEvent.skipped(current.campaign.id, version, current.trigger, CampaignSkipReason.LOWER_PRIORITY))
                } else {
                    record(CampaignEvent.skipped(campaign.id, version, selection.trigger.id, CampaignSkipReason.LOWER_PRIORITY))
                    return
                }
            }
            waiting = mine
        }
        val deadline = clock.now() + selection.trigger.waitSeconds * 1000L
        while (isBusy() || presentingFlow.value != null) {
            if (clock.now() >= deadline) {
                mutex.withLock {
                    if (waiting === mine) {
                        waiting = null
                        CampaignRules.busy(selection, version).records.forEach(record)
                    }
                }
                return
            }
            clock.sleep(250)
            if (waiting !== mine) return
        }
        val system = mutex.withLock {
            if (waiting !== mine) return
            waiting = null
            val current = state ?: return
            val now = clock.now()
            val onHand = set
            val recheck = if (onHand == null) {
                CampaignRecheck.NotShown(
                    CampaignOutcome.Skipped(CampaignSkipReason.WITHDRAWN),
                    listOf(CampaignEvent.skipped(campaign.id, version, selection.trigger.id, CampaignSkipReason.WITHDRAWN)),
                )
            } else {
                CampaignRules.recheck(selection, input(onHand, current, now, event))
            }
            val shown = when (recheck) {
                is CampaignRecheck.NotShown -> {
                    var next = current
                    recheck.records.forEach { next = report(it, next) }
                    state = next
                    storage.saveState(next)
                    return
                }
                is CampaignRecheck.Show -> recheck.selection
            }
            val setVersion = onHand!!.version
            val presentation = CampaignPresentation(
                id = UUID.randomUUID().toString(), campaign = shown.campaign.id, setVersion = setVersion,
                trigger = shown.trigger.id, placement = shown.campaign.placement, values = shown.values,
                event = event, onTap = shown.campaign.onTap,
            )
            val placement = placements[shown.campaign.placement]
            if (placement != null && runCatching { placement.decode(shown.values) }.isFailure) {
                val next = report(CampaignEvent.rejected(shown.campaign.id, setVersion, CampaignRejectReason.TYPE_MISMATCH), current)
                state = next
                storage.saveState(next)
                return
            }
            sessionShown += 1
            var history = current.history.counting(shown.campaign.id, now, context.appVersion)
            history = history.copy(shownAt = history.shownAt.filter { now - it < 2 * 86_400_000L } + now)
            val next = current.copy(history = history)
            state = next
            storage.saveState(next)
            record(CampaignEvent.shown(shown.campaign.id, setVersion, shown.trigger.id, shown.campaign.placement))
            val isSystem = placement?.kind == CampaignPlacementKind.SYSTEM ||
                registry.placements[shown.campaign.placement]?.kind == CampaignPlacementKind.SYSTEM
            if (isSystem) presentation else {
                presentingFlow.value = presentation
                null
            }
        } ?: return
        // A system placement has no look: its action runs at once, and no tap is recorded.
        complete(system)
    }

    // MARK: taps

    /** The primary button. Closes the placement, then runs the action or opens the link. */
    public suspend fun tap(presentation: CampaignPresentation) {
        if (presentingFlow.value?.id != presentation.id) return
        presentingFlow.value = null
        val target = if (presentation.onTap is CampaignTap.Link) "link" else "action"
        record(CampaignEvent.tapped(presentation.campaign, presentation.setVersion, presentation.trigger, target))
        complete(presentation)
    }

    private suspend fun complete(presentation: CampaignPresentation) {
        val (name, result) = when (val onTap = presentation.onTap) {
            is CampaignTap.Link -> {
                link(onTap.url)
                "link" to CampaignActionResult.COMPLETED
            }
            is CampaignTap.Action -> onTap.name to (actions[onTap.name]?.let { action ->
                runCatching {
                    action.run(CampaignActionRequest(presentation.campaign, presentation.setVersion, presentation.trigger, presentation.event, onTap.arguments))
                }.getOrDefault(CampaignActionResult.FAILED)
            } ?: CampaignActionResult.FAILED)
        }
        record(CampaignEvent.actionCompleted(presentation.campaign, presentation.setVersion, presentation.trigger, name, result))
        if (result != CampaignActionResult.COMPLETED) return
        update { it.updating(presentation.campaign) { record -> record.copy(actionCompletedAt = clock.now()) } }
    }

    /** Any way of closing without the primary button. */
    public suspend fun dismiss(presentation: CampaignPresentation, via: CampaignDismissal) {
        if (presentingFlow.value?.id != presentation.id) return
        presentingFlow.value = null
        record(CampaignEvent.dismissed(presentation.campaign, presentation.setVersion, presentation.trigger, via))
        update { it.updating(presentation.campaign) { record -> record.copy(dismissedAt = clock.now()) } }
    }

    private suspend fun update(change: (CampaignHistory) -> CampaignHistory) = mutex.withLock {
        val current = state ?: return@withLock
        val next = current.copy(history = change(current.history))
        state = next
        storage.saveState(next)
    }

    /** The presentation as [placement]'s values, when [placement] is the one being presented. */
    public fun <V> offer(placement: CampaignPlacement<V>): CampaignOffer<V>? {
        val presentation = presentingFlow.value ?: return null
        if (presentation.placement != placement.name) return null
        val values = runCatching { placement.decode(presentation.values) }.getOrNull() ?: return null
        return CampaignOffer(
            presentation = presentation,
            values = values,
            tap = { scope.launch { tap(presentation) } },
            dismiss = { via -> scope.launch { dismiss(presentation, via) } },
        )
    }

    // MARK: debug

    /**
     * Shows one campaign of the set at once, ignoring its triggers, audience, limits and holdout. For
     * the debug screen. Returns `false` when its text or image is not ready.
     */
    public suspend fun presentForDebug(campaign: String): Boolean {
        prepare()
        val set = set ?: return false
        val found = set.campaigns.firstOrNull { it.id == campaign } ?: return false
        val now = clock.now()
        val input = CampaignDecisionInput(
            set = set, registry = registry, fetchedAt = now, now = now, context = context, person = person(),
            daysSinceFirstSeen = 0, history = CampaignHistory(), sessionShown = 0, event = ObservedEvent("debug"),
            texts = texts, imagesReady = imagesReady.toSet(),
        )
        val values = CampaignRules.resolveValues(found, input) ?: return false
        presentingFlow.value = CampaignPresentation(
            id = UUID.randomUUID().toString(), campaign = found.id, setVersion = set.version, trigger = "debug",
            placement = found.placement, values = values, event = input.event, onTap = found.onTap,
        )
        return true
    }

    /**
     * Records a dismissal that happened before the SDK, so `after_dismiss_days` carries over (an
     * earlier "not now" of the same offer built directly into the app).
     */
    public suspend fun carryOverDismissal(campaign: String, at: Long) {
        prepare()
        update { history ->
            val current = history.record(campaign).dismissedAt ?: Long.MIN_VALUE
            if (at > current) history.updating(campaign) { it.copy(dismissedAt = at) } else history
        }
    }

    /** The counts on hand (debug screens and tests). */
    public val history: CampaignHistory get() = state?.history ?: CampaignHistory()

    private fun days(start: Long, end: Long): Int {
        val zone = context.timeZone
        val from = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val to = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        return maxOf(0, ChronoUnit.DAYS.between(from, to).toInt())
    }
}
