package com.appgrowstack.campaign

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs every table in `schema/cases` (copied by `scripts/sync-cases.sh`). Swift, the CLI and the
 * server read the same files, so a difference in the decision fails on every side.
 */
class SharedCasesTest {
    companion object {
        val directory: File = File(requireNotNull(SharedCasesTest::class.java.getResource("/cases")) { "run scripts/sync-cases.sh" }.toURI())
        val inapp: List<File> = File(directory, "inapp").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

        fun read(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun thereAreCases() {
        assertTrue(inapp.size >= 100, "${inapp.size} cases")
    }

    @TestFactory
    fun inappCases(): List<DynamicTest> = inapp.map { file ->
        DynamicTest.dynamicTest(file.nameWithoutExtension) { runTest { check(read(file)) } }
    }

    private suspend fun check(json: JsonObject) {
        val result = CaseRunner.run(json, File(directory, "inapp/manifests"))
        val expect = json.getValue("expect").jsonObject
        assertEquals(expect["show"], result.show?.let(::JsonPrimitive) ?: JsonNull, "show")
        assertEquals(expect["trigger"], result.trigger?.let(::JsonPrimitive) ?: JsonNull, "trigger")
        expect["values"]?.let { assertEquals(canonical(it), canonical(result.values), "values") }
        assertEquals(
            canonical(expect.getValue("outcomes")),
            canonical(JsonObject(result.outcomes.mapValues { JsonPrimitive(it.value) })),
            "outcomes",
        )
        assertEquals(
            expect.getValue("records").jsonArray.map(::canonical).sorted(),
            result.records.map(::canonical).sorted(),
            "records",
        )
        expect["server_facts_read"]?.let { read ->
            assertEquals(read.jsonArray.map { it.jsonPrimitive.content }, result.serverFactsRead, "server_facts_read")
        }
    }

    // MARK: experiment

    private fun vectors(name: String): JsonArray = read(File(directory, "experiment/$name.json")).getValue("vectors").jsonArray

    @Test
    fun hash32() {
        for (vector in vectors("hash32")) {
            val obj = vector.jsonObject
            assertEquals(obj.getValue("hash32").jsonPrimitive.long, Experiment.hash32(obj.getValue("input").jsonPrimitive.content), obj.toString())
        }
    }

    @Test
    fun holdout() {
        for (vector in vectors("holdout")) {
            val obj = vector.jsonObject
            val input = obj.getValue("input").jsonObject
            val project = input.getValue("project").jsonPrimitive.content
            val person = input.getValue("person_id").jsonPrimitive.content
            val campaign = input.getValue("campaign").jsonPrimitive.content
            assertEquals(obj.getValue("key").jsonPrimitive.content, Experiment.holdoutKey(project, person, campaign))
            assertEquals(obj.getValue("hash32").jsonPrimitive.long, Experiment.hash32(Experiment.holdoutKey(project, person, campaign)))
            val result = Experiment.assign(
                project, person, campaign,
                input.getValue("holdout").jsonPrimitive.double,
                input.getValue("internal").jsonPrimitive.booleanOrNull!!,
            )
            val expect = obj.getValue("expect").jsonObject
            assertEquals(expect.getValue("unit").jsonPrimitive.double, Experiment.round6(result.unit), obj.toString())
            assertEquals(expect.getValue("side").jsonPrimitive.content, result.side.code, obj.toString())
        }
    }

    @Test
    fun reach() {
        for (vector in vectors("reach")) {
            val obj = vector.jsonObject
            val input = obj.getValue("input").jsonObject
            val result = Experiment.reach(
                input.getValue("first_eligible_at").jsonPrimitive.long,
                input.getValue("within_hours").jsonPrimitive.double,
                input.getValue("goal_at").jsonArray.map { it.jsonPrimitive.long },
                input.getValue("now").jsonPrimitive.long,
            )
            val expect = obj.getValue("expect").jsonObject
            val description = obj.getValue("description").jsonPrimitive.content
            assertEquals(expect.getValue("counted").jsonPrimitive.booleanOrNull, result.counted, description)
            assertEquals(expect.getValue("reached").jsonPrimitive.booleanOrNull, result.reached, description)
        }
    }

    @Test
    fun compare() {
        fun arm(json: JsonElement) = json.jsonObject.let { Experiment.Arm(it.getValue("eligible").jsonPrimitive.int, it.getValue("reached").jsonPrimitive.int) }
        fun number(json: JsonElement?) = (json as? JsonPrimitive)?.takeIf { it !is JsonNull }?.double
        for (vector in vectors("compare")) {
            val obj = vector.jsonObject
            val input = obj.getValue("input").jsonObject
            val result = Experiment.compare(arm(input.getValue("treatment")), arm(input.getValue("holdout")), input.getValue("min_per_arm").jsonPrimitive.int)
            val expect = obj.getValue("expect").jsonObject
            val description = obj.getValue("description").jsonPrimitive.content
            assertEquals(expect.getValue("status").jsonPrimitive.content, if (result.compared) "compared" else "too_few", description)
            assertEquals(number(expect["treatment_rate"]), result.treatmentRate, "$description treatment_rate")
            assertEquals(number(expect["holdout_rate"]), result.holdoutRate, "$description holdout_rate")
            assertEquals(number(expect["diff"]), result.diff, "$description diff")
            assertEquals(number(expect["low"]), result.low, "$description low")
            assertEquals(number(expect["high"]), result.high, "$description high")
        }
    }
}

/** JSON with sorted keys and numbers written one way, so two equal values print the same. */
fun canonical(json: JsonElement): String = when (json) {
    is JsonObject -> json.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${canonical(it.value)}" }
    is JsonArray -> json.joinToString(",", "[", "]") { canonical(it) }
    is JsonNull -> "null"
    is JsonPrimitive -> if (json.isString) json.toString() else CampaignValue.fromJson(json)?.let {
        when (it) {
            is CampaignValue.IntValue -> it.value.toString()
            is CampaignValue.DoubleValue -> if (it.value == Math.floor(it.value)) it.value.toLong().toString() else it.value.toString()
            else -> json.content
        }
    } ?: json.content
}

/** Runs one table of `schema/cases/inapp` through the loader, the decision and, with `wait`, the recheck. */
object CaseRunner {
    data class Result(
        var show: String? = null,
        var trigger: String? = null,
        var values: JsonElement = JsonNull,
        val outcomes: MutableMap<String, String> = linkedMapOf(),
        val records: MutableList<JsonElement> = mutableListOf(),
        var serverFactsRead: List<String> = emptyList(),
    )

    suspend fun run(json: JsonObject, manifests: File): Result {
        val manifest = json.getValue("manifest").jsonPrimitive.content
        val registry = CampaignManifest.registry(File(manifests, "$manifest.json").readText())
        val text = (json["set_raw"] as? JsonPrimitive)?.content ?: json.getValue("set").toString()

        val result = Result()
        val set = when (val loaded = CampaignSetLoader.load(text, registry)) {
            is CampaignSetLoader.Result.Unusable -> {
                result.records.add(event(CampaignEvent.rejected("_set", loaded.version, loaded.reason)))
                return result
            }
            is CampaignSetLoader.Result.Loaded -> {
                for (rejection in loaded.rejections) {
                    result.outcomes[rejection.campaign] = CampaignOutcome.Rejected(rejection.reason).code
                    result.records.add(event(CampaignEvent.rejected(rejection.campaign, loaded.set.version, rejection.reason)))
                }
                loaded.set
            }
        }

        val context = json.getValue("context").jsonObject
        val facts = json.getValue("facts").jsonObject.mapValues { CampaignValue.fromJson(it.value) }
        val texts = json.getValue("texts").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val eventJson = json.getValue("event").jsonObject
        val input = CampaignDecisionInput(
            set = set,
            registry = registry,
            fetchedAt = time(json.getValue("fetched_at")),
            now = time(json.getValue("now")),
            context = CampaignContext(
                platform = context.string("platform"),
                appVersion = context.string("app_version"),
                build = context.string("build"),
                channel = context.string("channel"),
                lang = context.string("lang"),
                region = context.string("region"),
                timeZone = ZoneId.of(context.string("time_zone")),
                internalDevice = context.getValue("internal_device").jsonPrimitive.booleanOrNull!!,
            ),
            person = json.getValue("person").jsonPrimitive.content,
            daysSinceFirstSeen = context.getValue("days_since_first_seen").jsonPrimitive.int,
            sessionSeconds = context["session_seconds"]?.jsonPrimitive?.long,
            history = history(json.getValue("history").jsonObject),
            sessionShown = json.getValue("history").jsonObject.getValue("session_shown").jsonPrimitive.int,
            event = ObservedEvent(
                eventJson.string("name"),
                eventJson.getValue("props").jsonObject.mapValues { requireNotNull(CampaignValue.fromJson(it.value)) },
            ),
            texts = { texts[it] },
            imagesReady = json.getValue("images_ready").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        val decision = CampaignRules.decide(input) { facts[it] }
        decision.outcomes.forEach { (id, outcome) -> result.outcomes[id] = outcome.code }
        result.records += decision.records.map(::event)
        result.serverFactsRead = decision.serverFactsRead

        var shown = decision.shown
        val wait = json["wait"] as? JsonObject
        if (shown != null && wait != null) {
            val busy = wait.getValue("busy_seconds").jsonPrimitive.int
            val recheck = if (busy > shown.trigger.waitSeconds) {
                CampaignRules.busy(shown, set.version)
            } else {
                val later = (wait["set"] as? JsonObject)?.let { reload(it, registry) } ?: set
                val laterHistory = wait["history"] as? JsonObject
                CampaignRules.recheck(shown, input.copy(
                    set = later,
                    now = input.now + busy * 1000L,
                    history = laterHistory?.let(::history) ?: input.history,
                    sessionShown = laterHistory?.getValue("session_shown")?.jsonPrimitive?.int ?: input.sessionShown,
                ))
            }
            shown = when (recheck) {
                is CampaignRecheck.Show -> recheck.selection
                is CampaignRecheck.NotShown -> {
                    result.outcomes[shown.campaign.id] = recheck.outcome.code
                    result.records += recheck.records.map(::event)
                    null
                }
            }
        }
        if (shown != null) {
            result.show = shown.campaign.id
            result.trigger = shown.trigger.id
            result.values = JsonObject(shown.values.mapValues { it.value.toJson() })
        }
        return result
    }

    private fun reload(json: JsonObject, registry: CampaignRegistry): CampaignSet =
        (CampaignSetLoader.load(json.toString(), registry) as CampaignSetLoader.Result.Loaded).set

    private fun history(json: JsonObject) = CampaignHistory(
        shownAt = json.getValue("shown_at").jsonArray.map(::time),
        campaigns = json.getValue("campaigns").jsonObject.mapValues { (_, value) ->
            val record = value.jsonObject
            CampaignRecord(
                shownCount = record["shown_count"]?.jsonPrimitive?.int ?: 0,
                lastShownAt = optionalTime(record["last_shown_at"]),
                shownByAppVersion = record["shown_by_app_version"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.int },
                dismissedAt = optionalTime(record["dismissed_at"]),
                actionCompletedAt = optionalTime(record["action_completed_at"]),
            )
        },
    )

    fun event(event: CampaignEvent): JsonElement = JsonObject(mapOf(
        "name" to JsonPrimitive(event.name),
        "params" to JsonObject(event.params.mapValues { it.value.toJson() }),
    ))

    private fun time(json: JsonElement): Long = requireNotNull(CampaignTime.parse(json.jsonPrimitive.content)) { "time: $json" }
    private fun optionalTime(json: JsonElement?): Long? = if (json == null || json is JsonNull) null else time(json)
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
