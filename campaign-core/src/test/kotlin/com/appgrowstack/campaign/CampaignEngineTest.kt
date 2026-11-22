package com.appgrowstack.campaign

import com.appgrowstack.campaign.testing.CampaignEventRecorder
import com.appgrowstack.campaign.testing.FakeCampaignClock
import com.appgrowstack.campaign.testing.FakeCampaignSetFetcher
import com.appgrowstack.campaign.testing.FakeImagePrefetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private inner class Harness(
        val person: String = "user-6",
        val busy: () -> Boolean = { false },
        appVersion: String = "3.3.0",
        /** Installed ten days ago, so `days_since_first_seen` conditions pass. */
        stored: CampaignStoredState? = CampaignStoredState(firstSeenAt = start - 10 * 86_400_000L, lastAppVersion = appVersion),
        firstSeenAt: Long? = null,
    ) {
        val fetcher = FakeCampaignSetFetcher()
        val storage = InMemoryCampaignStorage(state = stored)
        val clock = FakeCampaignClock(start)
        val recorder = CampaignEventRecorder()
        val reviews = mutableListOf<CampaignActionRequest>()
        val trials: MutableList<CampaignActionRequest> = java.util.concurrent.CopyOnWriteArrayList()
        /** Thrown by start_trial when set (the action's coroutine was cancelled, a failing action). */
        var trialThrows: Exception? = null
        val context = CampaignContext("3.3.0", "330", "play", "ja", "JP", ZoneId.of("Asia/Tokyo"), firstSeenAt = firstSeenAt)
        val engine = engine(appVersion)

        fun engine(appVersion: String) = CampaignEngine(
            context = context.copy(appVersion = appVersion),
            person = { person },
            placements = listOf(trialOffer, banner, whatsNew, CampaignPlacement.system("review_ask"), CampaignSurvey.placement),
            facts = listOf(
                CampaignFact.device("is_premium", CampaignFactType.BOOL) { CampaignValue.of(false) },
                CampaignFact.device("has_trial_grant", CampaignFactType.BOOL) { CampaignValue.of(false) },
                CampaignFact.device("achieved_days", CampaignFactType.INT) { CampaignValue.of(5) },
                CampaignFact.server("trial_eligible", CampaignFactType.BOOL) { CampaignValue.of(true) },
            ),
            actions = listOf(
                CampaignAction("start_trial") { trialThrows?.let { e -> throw e }; trials.add(it); CampaignActionResult.COMPLETED },
                CampaignAction("request_review") { reviews.add(it); CampaignActionResult.COMPLETED },
                CampaignAction.submitAnswer(),
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
    fun appsOwnFirstSeenDateCountsWhenEarlier() = runTest {
        val tenDaysAgo = start - 10 * 86_400_000L
        suspend fun storedAfterLaunch(h: Harness): Long? {
            h.load(caseSet("show-after-paywall"))
            return h.storage.loadState()?.firstSeenAt
        }
        // A new state takes the app's date from the first launch; without one it is today.
        assertEquals(tenDaysAgo, storedAfterLaunch(Harness(stored = null, firstSeenAt = tenDaysAgo)))
        assertEquals(start, storedAfterLaunch(Harness(stored = null)))
        // A stored date of today moves to the app's earlier date; a later date never moves it.
        assertEquals(tenDaysAgo, storedAfterLaunch(Harness(stored = CampaignStoredState(firstSeenAt = start, lastAppVersion = "3.3.0"), firstSeenAt = tenDaysAgo)))
        assertEquals(tenDaysAgo, storedAfterLaunch(Harness(firstSeenAt = start)))
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
    fun aShownVariantIsRecordedOnItsEvents() = runTest {
        // user-6 lands on the second variant (warm) of the shared table.
        val h = Harness()
        h.load(caseSet("variant-second"))
        h.engine.receive(paywallClosed)

        val offer = assertNotNull(h.engine.offer(trialOffer))
        assertEquals("2日間、ぜんぶ使えます", offer.values.headline)
        assertEquals("warm", offer.presentation.variant)
        val eligible = h.recorder.named("campaign_eligible").single()
        assertEquals(CampaignValue.of("warm"), eligible.params["variant"])
        assertEquals(CampaignValue.of(5), eligible.params["policy_version"])
        val shown = h.recorder.named("campaign_shown").single()
        assertEquals(CampaignValue.of("warm"), shown.params["variant"])

        h.engine.dismiss(offer.presentation, CampaignDismissal.SECONDARY)
        assertEquals(CampaignValue.of("warm"), h.recorder.named("campaign_dismissed").single().params["variant"])
    }

    @Test
    fun aCancelledActionIsNotRecordedAsFailed() = runTest {
        val h = Harness()
        h.load(caseSet("show-after-paywall"))
        h.engine.receive(paywallClosed)
        val offer = assertNotNull(h.engine.offer(trialOffer))
        h.trialThrows = CancellationException("the screen went away")
        assertFailsWith<CancellationException> { h.engine.tap(offer.presentation) }
        assertEquals(listOf("campaign_eligible", "campaign_shown", "campaign_tapped"), h.recorder.events.map { it.name })
    }

    @Test
    fun aThrowingActionIsRecordedAsFailed() = runTest {
        val h = Harness()
        h.load(caseSet("show-after-paywall"))
        h.engine.receive(paywallClosed)
        val offer = assertNotNull(h.engine.offer(trialOffer))
        h.trialThrows = IllegalStateException("billing unavailable")
        h.engine.tap(offer.presentation)
        val completed = h.recorder.named("campaign_action_completed").single()
        assertEquals(CampaignValue.of("failed"), completed.params["result"])
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

    /** [caseSet] with every campaign's holdout set to [holdout]. */
    private fun withHoldout(name: String, holdout: Double): String = caseSet(name) { set ->
        JsonObject(set + ("campaigns" to JsonArray(set.getValue("campaigns").jsonArray.map { campaign ->
            JsonObject(campaign.jsonObject + ("holdout" to JsonPrimitive(holdout)))
        })))
    }

    @Test
    fun aSideOnceDecidedStaysWhenTheHoldoutChanges() = runTest {
        val h = Harness(person = "user-1")
        h.load(caseSet("show-after-paywall"))
        h.engine.receive(paywallClosed)
        assertEquals(mapOf("trial-2days-after-feature" to Experiment.Side.HOLDOUT), h.engine.sides)
        assertEquals(mapOf("trial-2days-after-feature" to Experiment.Side.HOLDOUT), h.storage.loadState()?.sides)

        // No one is held out by the new set's share, but this person's side was already decided.
        h.clock.advance(25 * 3_600_000L)
        h.load(withHoldout("show-after-paywall", 0.0))
        h.engine.receive(paywallClosed)

        assertNull(h.engine.presenting.value)
        assertEquals(listOf(CampaignValue.of("holdout"), CampaignValue.of("holdout")), h.recorder.named("campaign_eligible").map { it.params["side"] })
    }

    @Test
    fun theSideIsKeptAsTheSwiftSdkKeepsIt() {
        val state = CampaignStoredState(firstSeenAt = 0, sides = mapOf("a" to Experiment.Side.HOLDOUT, "b" to Experiment.Side.TREATMENT))
        val text = Json.encodeToString(CampaignStoredState.serializer(), state)
        assertTrue(""""sides":{"a":"holdout","b":"treatment"}""" in text, text)
        assertEquals(state, Json.decodeFromString(CampaignStoredState.serializer(), text))
    }

    @Test
    fun aTapAndADismissalAtOnceRecordOnlyOne() = runBlocking {
        repeat(300) { round ->
            val h = Harness()
            h.load(caseSet("show-after-paywall"))
            h.engine.receive(paywallClosed)
            val presentation = assertNotNull(h.engine.presenting.value)
            val go = CountDownLatch(1)
            val callers = listOf(
                thread { go.await(); runBlocking { h.engine.tap(presentation) } },
                thread { go.await(); runBlocking { h.engine.dismiss(presentation, CampaignDismissal.SECONDARY) } },
                thread { go.await(); runBlocking { h.engine.tap(presentation) } },
            )
            go.countDown()
            callers.forEach { it.join() }
            val closed = h.recorder.events.count { it.name == "campaign_tapped" || it.name == "campaign_dismissed" }
            assertEquals(1, closed, "round $round: ${h.recorder.events.map { it.name }}")
            assertTrue(h.trials.size <= 1, "round $round: the action ran ${h.trials.size} times")
        }
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

    // MARK: survey

    private val bookAdded = ObservedEvent("book_added")

    private fun surveySet(values: String = """{"question":{"ja":"今は Premium にしない理由は？"},"choice_1":{"ja":"値段"},"choice_2":{"ja":"まだ分からない"},"choice_3":{"ja":"ほか"},"free_text":true}""", trigger: String = "book_added"): String {
        val survey = Json.parseToJsonElement("""
            {"schema_version":1,"id":"survey-why-not-premium","type":"survey","status":"active","title":"理由を聞く","purpose":"理由",
             "priority":40,"platforms":["ios","android"],"app_version":{"ios":">=3.3.0","android":">=3.3.0"},
             "starts_at":null,"ends_at":null,
             "triggers":[{"id":"after","event":"$trigger","where":null,"wait_seconds":0}],
             "audience":null,
             "limits":{"max_shown":1,"min_hours_between":0,"after_dismiss_days":30,"after_action_days":null,"not_after_in_launch":[{"event":"paywall_closed","where":null}]},
             "holdout":0,
             "show":{"placement":"survey","values":$values},
             "on_tap":{"action":"submit_answer","args":{}},
             "goals":[{"event":"survey_answered","where":null,"within_hours":1}]}
        """).jsonObject
        return caseSet("show-after-paywall") { JsonObject(it + ("campaigns" to JsonArray(listOf(survey)))) }
    }

    @Test
    fun surveyIsASheetAndAnAnswerIsRecordedOnce() = runTest {
        val h = Harness()
        h.load(surveySet())
        h.engine.receive(bookAdded)

        val offer = assertNotNull(h.engine.offer(CampaignSurvey.placement))
        assertEquals("今は Premium にしない理由は？", offer.values.question)
        assertEquals(listOf("choice_1", "choice_2", "choice_3"), offer.values.choices.map { it.id })
        assertTrue(offer.values.asksForText)

        // An id the campaign does not have is ignored and the sheet stays.
        h.engine.answer(offer.presentation, "choice_5")
        h.engine.answer(offer.presentation, "7")
        assertNotNull(h.engine.presenting.value)
        assertTrue(h.recorder.named("survey_answered").isEmpty())

        h.engine.answer(offer.presentation, "choice_2")
        assertNull(h.engine.presenting.value)
        val answered = h.recorder.named("survey_answered").single()
        assertEquals(CampaignValue.of("choice_2"), answered.params["choice"])
        assertEquals(CampaignValue.of("survey-why-not-premium"), answered.params["campaign"])
        val done = h.recorder.named("campaign_action_completed").single()
        assertEquals(CampaignValue.of("submit_answer"), done.params["action"])
        assertEquals(CampaignValue.of("completed"), done.params["result"])

        h.engine.answer(offer.presentation, "choice_1")
        assertEquals(1, h.recorder.named("survey_answered").size)
    }

    @Test
    fun surveyScaleAnswersAreNumbersUpToTheScale() = runTest {
        val h = Harness()
        h.load(surveySet("""{"question":{"ja":"友達に勧めますか？"},"choice_1":{"ja":"勧めない"},"choice_2":{"ja":"勧める"},"scale":10}"""))
        h.engine.receive(bookAdded)
        val offer = assertNotNull(h.engine.offer(CampaignSurvey.placement))
        assertEquals(11, offer.values.choices.size)
        assertEquals("勧めない", offer.values.choices.first().label)
        assertEquals("勧める", offer.values.choices.last().label)
        h.engine.answer(offer.presentation, "11")
        h.engine.answer(offer.presentation, "choice_1")
        assertTrue(h.recorder.named("survey_answered").isEmpty())
        h.engine.answer(offer.presentation, "10")
        assertEquals(CampaignValue.of("10"), h.recorder.named("survey_answered").single().params["choice"])
    }

    @Test
    fun noSurveyInTheLaunchThePaywallWasClosed() = runTest {
        val h = Harness()
        h.load(surveySet())
        h.engine.receive(paywallClosed)
        h.engine.receive(bookAdded)
        assertNull(h.engine.presenting.value)
        assertTrue(h.recorder.named("campaign_eligible").isEmpty())

        // The next launch (a new engine on the same storage) asks.
        val next = h.engine("3.3.0")
        h.fetcher.enqueueSet(surveySet(), "\"v7\"")
        next.refresh()
        next.receive(bookAdded)
        assertNotNull(next.offer(CampaignSurvey.placement))
    }

    @Test
    fun surveyAnswersNeverTrigger() = runTest {
        val h = Harness()
        h.load(surveySet(trigger = "survey_answered"))
        h.engine.receive(ObservedEvent("survey_answered", mapOf("choice" to CampaignValue.of("choice_1"))))
        assertNull(h.engine.presenting.value)
        assertTrue(h.recorder.named("campaign_eligible").isEmpty())
    }
}
