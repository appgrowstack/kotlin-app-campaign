package com.appgrowstack.campaign

import com.appgrowstack.campaign.testing.CampaignEventRecorder
import com.appgrowstack.campaign.testing.FakeCampaignClock
import com.appgrowstack.campaign.testing.FakeCampaignSetFetcher
import com.appgrowstack.campaign.testing.FakeImagePrefetcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class TrialOfferValues(
    val headline: String,
    val body: String,
    @SerialName("primary_label") val primaryLabel: String,
    @SerialName("secondary_label") val secondaryLabel: String,
    val image: CampaignImage? = null,
)

class CampaignEngineTest {
    private val start = CampaignTime.parse("2026-12-10T12:00:00+09:00")!!
    private val trialOffer = CampaignPlacement.of(
        "trial_offer", TrialOfferValues.serializer(),
        mapOf(
            "headline" to CampaignField.text(maxChars = 24), "body" to CampaignField.text(maxChars = 120),
            "primary_label" to CampaignField.text(maxChars = 24), "secondary_label" to CampaignField.text(maxChars = 12),
            "image" to CampaignField.image(),
        ),
    )
    /** Placements the app shows with its own look; here the values stay as they are. */
    private fun plain(name: String, fields: Map<String, CampaignField>) =
        CampaignPlacement(name, CampaignPlacementKind.MODAL, fields) { it }
    private val banner = plain("banner", mapOf(
        "title" to CampaignField.text(maxChars = 30), "note" to CampaignField.text(required = false),
        "badge" to CampaignField.string(), "count" to CampaignField.int(), "emphasize" to CampaignField.bool(),
        "image" to CampaignField.image(),
    ))
    private val whatsNew = plain("whats_new", mapOf(
        "headline" to CampaignField.text(maxChars = 20), "body" to CampaignField.text(maxChars = 60),
        "image" to CampaignField.image(), "primary_label" to CampaignField.text(required = false, maxChars = 16),
    ))
    private val paywallClosed = ObservedEvent("paywall_closed", mapOf("result" to CampaignValue.of("continue_free"), "source" to CampaignValue.of("habit_limit")))

    /** The set of one of the shared tables, so the engine and the tables read the same campaigns. */
    private fun caseSet(name: String, change: (JsonObject) -> JsonObject = { it }): String {
        val file = File(SharedCasesTest.directory, "inapp/$name.json")
        return change(SharedCasesTest.read(file).getValue("set").jsonObject).toString()
    }

    private inner class Harness(val person: String = "user-6", val busy: () -> Boolean = { false }, appVersion: String = "3.3.0") {
        val fetcher = FakeCampaignSetFetcher()
        /** Installed ten days ago, so `days_since_first_seen` conditions pass. */
        val storage = InMemoryCampaignStorage(state = CampaignStoredState(firstSeenAt = start - 10 * 86_400_000L, lastAppVersion = appVersion))
        val clock = FakeCampaignClock(start)
        val recorder = CampaignEventRecorder()
        val reviews = mutableListOf<CampaignActionRequest>()
        val trials = mutableListOf<CampaignActionRequest>()
        val context = CampaignContext("3.3.0", "330", "play", "ja", "JP", ZoneId.of("Asia/Tokyo"))
        val engine = engine(appVersion)

        fun engine(appVersion: String) = CampaignEngine(
            projectKey = "pk_test",
            context = context.copy(appVersion = appVersion),
            person = { person },
            placements = listOf(trialOffer, banner, whatsNew, CampaignPlacement.system("review_ask")),
            facts = listOf(
                CampaignFact.device("is_premium", CampaignFactType.BOOL) { CampaignValue.of(false) },
                CampaignFact.device("has_trial_grant", CampaignFactType.BOOL) { CampaignValue.of(false) },
                CampaignFact.device("achieved_days", CampaignFactType.INT) { CampaignValue.of(5) },
                CampaignFact.server("trial_eligible", CampaignFactType.BOOL) { CampaignValue.of(true) },
            ),
            actions = listOf(
                CampaignAction("start_trial") { trials.add(it); CampaignActionResult.COMPLETED },
                CampaignAction("request_review") { reviews.add(it); CampaignActionResult.COMPLETED },
                CampaignAction("open_habit", mapOf(
                    "habit" to CampaignArgument(CampaignArgumentType.STRING, true),
                    "days" to CampaignArgument(CampaignArgumentType.INT, false),
                )) { CampaignActionResult.COMPLETED },
            ),
            assets = setOf("trial_gift"),
            storage = storage,
            record = recorder.record,
            fetcher = fetcher,
            isBusy = busy,
            clock = clock,
            images = FakeImagePrefetcher(),
        )

        suspend fun load(body: String) {
            fetcher.enqueueSet(body, "\"v7\"")
            engine.refresh()
        }
    }

    @Test
    fun showsTheOfferAndTapRunsTheAction() = runTest {
        val h = Harness()
        h.load(caseSet("show-after-paywall"))
        h.engine.receive(paywallClosed)

        val offer = assertNotNull(h.engine.offer(trialOffer))
        assertEquals("プレミアムを2日間、無料で", offer.values.headline)
        assertEquals(listOf("campaign_eligible", "campaign_shown"), h.recorder.events.map { it.name })

        h.engine.tap(offer.presentation)
        assertNull(h.engine.presenting.value)
        assertEquals(1, h.trials.size)
        assertEquals("after_paywall", h.trials.single().trigger)
        val record = h.engine.history.record("trial-2days-after-feature")
        assertEquals(1, record.shownCount)
        assertEquals(mapOf("3.3.0" to 1), record.shownByAppVersion)
        assertNotNull(record.actionCompletedAt)
        assertEquals(listOf("campaign_eligible", "campaign_shown", "campaign_tapped", "campaign_action_completed"), h.recorder.events.map { it.name })
    }

    @Test
    fun heldOutTurnsCountLikeShownOnes() = runTest {
        val h = Harness(person = "user-1")
        h.load(caseSet("show-after-paywall"))
        h.engine.receive(paywallClosed)

        assertNull(h.engine.presenting.value)
        val eligible = h.recorder.named("campaign_eligible").single()
        assertEquals(CampaignValue.of("holdout"), eligible.params["side"])
        assertEquals(1, h.engine.history.record("trial-2days-after-feature").shownCount)
        assertTrue(h.engine.history.shownAt.isEmpty())
    }

    @Test
    fun waitsWhileBusyThenShows() = runTest {
        var busyUntil = start + 120_000
        lateinit var h: Harness
        h = Harness(busy = { h.clock.now() < busyUntil })
        h.load(caseSet("show-after-paywall"))
        h.engine.receive(ObservedEvent("exchange_completed"))

        assertNotNull(h.engine.presenting.value)
        assertTrue(h.clock.now() >= busyUntil)
    }

    @Test
    fun busyLongerThanTheWaitSkips() = runTest {
        val h = Harness(busy = { true })
        h.load(caseSet("show-after-paywall"))
        h.engine.receive(paywallClosed)

        assertNull(h.engine.presenting.value)
        val skipped = h.recorder.named("campaign_skipped").single()
        assertEquals(CampaignValue.of("busy"), skipped.params["reason"])
    }

    @Test
    fun aSystemPlacementRunsItsActionWithoutATap() = runTest {
        val h = Harness()
        h.load(caseSet("type-review-ask-show"))
        h.engine.receive(ObservedEvent("exchange_completed", mapOf("count" to CampaignValue.of(3))))

        assertNull(h.engine.presenting.value)
        assertEquals(1, h.reviews.size)
        assertEquals(listOf("campaign_eligible", "campaign_shown", "campaign_action_completed"), h.recorder.events.map { it.name })
        assertEquals(1, h.engine.history.record("review-after-third-exchange").shownByAppVersion["3.3.0"])
    }

    @Test
    fun dismissingKeepsItAwayAndTheSessionLimitHolds() = runTest {
        val h = Harness()
        h.load(caseSet("show-after-paywall"))
        h.engine.receive(paywallClosed)
        h.engine.dismiss(h.engine.presenting.value!!, CampaignDismissal.SECONDARY)
        h.clock.advance(25 * 3_600_000L)
        h.fetcher.enqueueNotModified()
        h.engine.refresh()
        h.engine.receive(paywallClosed)

        assertNull(h.engine.presenting.value)
        assertEquals(1, h.recorder.named("campaign_shown").size)
        assertEquals("\"v7\"", h.fetcher.etags.last())
    }

    @Test
    fun aBrokenCampaignIsReportedOnceAndTheRestIsUsed() = runTest {
        val h = Harness()
        val body = caseSet("rejected-unknown-placement")
        h.load(body)
        h.fetcher.enqueueSet(body, "\"v7\"")
        h.engine.refresh()

        assertEquals(1, h.recorder.named("campaign_rejected").size)
        assertEquals(listOf("aaa-offer"), h.engine.set?.campaigns?.map { it.id })
        h.engine.receive(paywallClosed)
        assertEquals("banner", h.engine.presenting.value?.placement)
    }

    @Test
    fun offlineTheSetOnHandIsUsedUntilCacheHours() = runTest {
        val h = Harness()
        h.load(caseSet("show-after-paywall"))
        h.clock.advance(73 * 3_600_000L)
        h.engine.refresh() // offline
        h.engine.receive(paywallClosed)
        h.engine.receive(paywallClosed)

        assertNull(h.engine.presenting.value)
        assertEquals(1, h.recorder.named("campaigns_stale").size)
    }

    @Test
    fun theStateAndTheSetSurviveARelaunch() = runTest {
        val h = Harness()
        h.load(caseSet("show-after-paywall"))
        h.engine.receive(paywallClosed)
        h.engine.dismiss(h.engine.presenting.value!!, CampaignDismissal.CLOSE_BUTTON)

        val again = h.engine("3.3.0")
        again.receive(paywallClosed)
        assertNull(again.presenting.value)
        assertNotNull(again.history.record("trial-2days-after-feature").dismissedAt)
    }

    @Test
    fun anUpdateRecordsAppUpdatedAndTriggersWhatsNew() = runTest {
        val h = Harness(appVersion = "3.2.0")
        h.load(caseSet("type-whats-new-show"))
        h.recorder.events.clear()

        val updated = h.engine("3.3.0")
        h.fetcher.enqueueNotModified()
        updated.refresh()

        val event = h.recorder.named("app_updated").single()
        assertEquals(CampaignValue.of("3.2.0"), event.params["from_version"])
        assertEquals("whats-new-3-3-0-auto-check", updated.presenting.value?.campaign)
    }

    @Test
    fun valuesTheAppCannotDecodeAreATypeMismatch() = runTest {
        @Serializable data class Wrong(val headline: Int)
        val wrong = CampaignPlacement.of("trial_offer", Wrong.serializer(), trialOffer.fields)
        val recorder = CampaignEventRecorder()
        val fetcher = FakeCampaignSetFetcher()
        val engine = CampaignEngine(
            projectKey = "pk_test",
            context = CampaignContext("3.3.0", "330", "play", "ja", "JP", ZoneId.of("Asia/Tokyo")),
            person = { "user-6" },
            placements = listOf(wrong),
            facts = listOf(
                CampaignFact.device("is_premium", CampaignFactType.BOOL) { CampaignValue.of(false) },
                CampaignFact.device("has_trial_grant", CampaignFactType.BOOL) { CampaignValue.of(false) },
                CampaignFact.device("achieved_days", CampaignFactType.INT) { CampaignValue.of(5) },
                CampaignFact.server("trial_eligible", CampaignFactType.BOOL) { CampaignValue.of(true) },
            ),
            actions = listOf(CampaignAction("start_trial") { CampaignActionResult.COMPLETED }),
            storage = InMemoryCampaignStorage(),
            record = recorder.record,
            fetcher = fetcher,
            clock = FakeCampaignClock(start),
            images = FakeImagePrefetcher(),
        )
        fetcher.enqueueSet(caseSet("show-after-paywall"))
        engine.refresh()
        engine.receive(paywallClosed)

        assertNull(engine.presenting.value)
        assertEquals(CampaignValue.of("type_mismatch"), recorder.named("campaign_rejected").single().params["reason"])
    }

    @Test
    fun campaignEventsAreNotTriggers() = runTest {
        val h = Harness()
        h.load(caseSet("show-after-paywall") { set ->
            val campaigns = set.getValue("campaigns").jsonArray.map { campaign ->
                val obj = campaign.jsonObject
                val trigger = JsonObject(mapOf(
                    "id" to JsonPrimitive("after_shown"), "event" to JsonPrimitive("campaign_shown"),
                    "where" to kotlinx.serialization.json.JsonNull, "wait_seconds" to JsonPrimitive(0),
                ))
                JsonObject(obj + ("triggers" to JsonArray(listOf(trigger))))
            }
            JsonObject(set + ("campaigns" to JsonArray(campaigns)))
        })
        h.engine.receive(ObservedEvent("campaign_shown"))
        assertTrue(h.recorder.events.isEmpty())
    }
}
