package com.appgrowstack.campaign

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The type of a value a placement receives (the manifest's `fields.*.type`). */
public enum class CampaignFieldType(public val code: String) {
    /** Text per language. The SDK picks the language and hands the placement a `String`. */
    TEXT("text"),
    /** [CampaignImage]: an image in the app, or an HTTPS image fetched ahead of time. */
    IMAGE("image"),
    INT("int"),
    BOOL("bool"),
    /** A string that is the same in every language (an id, a colour name). Never shown as copy. */
    STRING("string"),
    ;

    public companion object {
        public fun of(code: String): CampaignFieldType? = entries.firstOrNull { it.code == code }
    }
}

public data class CampaignField(val type: CampaignFieldType, val required: Boolean, val maxChars: Int? = null) {
    public companion object {
        public fun text(required: Boolean = true, maxChars: Int? = null): CampaignField = CampaignField(CampaignFieldType.TEXT, required, maxChars)
        public fun image(required: Boolean = false): CampaignField = CampaignField(CampaignFieldType.IMAGE, required)
        public fun int(required: Boolean = false): CampaignField = CampaignField(CampaignFieldType.INT, required)
        public fun bool(required: Boolean = false): CampaignField = CampaignField(CampaignFieldType.BOOL, required)
        public fun string(required: Boolean = false): CampaignField = CampaignField(CampaignFieldType.STRING, required)
    }
}

public enum class CampaignPlacementKind(public val code: String) {
    /** The SDK says "show this now" and the app presents it in its own way. */
    MODAL("modal"),
    /** The same as [MODAL], presented from the bottom at about half height (the `survey` question). */
    SHEET("sheet"),
    /** No look of its own: showing it runs `on_tap` at once (the OS review prompt). No tap is recorded. */
    SYSTEM("system"),
    ;

    public companion object {
        public fun of(code: String): CampaignPlacementKind? = entries.firstOrNull { it.code == code }
    }
}

/** A placement as the SDK checks it: a name, a kind and its fields. */
public data class CampaignPlacementSpec(
    val name: String,
    val kind: CampaignPlacementKind = CampaignPlacementKind.MODAL,
    val fields: Map<String, CampaignField>,
)

/**
 * A place in the app that can show a campaign. The app owns its look; a campaign only fills it.
 *
 * ```kotlin
 * @Serializable
 * data class TrialOfferValues(
 *     val headline: String, val body: String,
 *     @SerialName("primary_label") val primaryLabel: String,
 *     @SerialName("secondary_label") val secondaryLabel: String,
 *     val image: CampaignImage? = null,
 * )
 * val trialOffer = CampaignPlacement.of("trial_offer", TrialOfferValues.serializer(), mapOf(
 *     "headline" to CampaignField.text(maxChars = 24), …
 * ))
 * ```
 */
public class CampaignPlacement<V>(
    public val name: String,
    public val kind: CampaignPlacementKind,
    public val fields: Map<String, CampaignField>,
    /** Turns the resolved values into the app's type. Throws when they do not fit (the app's bug). */
    public val decode: (Map<String, CampaignResolvedValue>) -> V,
) {
    public val spec: CampaignPlacementSpec get() = CampaignPlacementSpec(name, kind, fields)

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Values decoded with kotlinx.serialization from the resolved values (snake_case keys). */
        public fun <V> of(
            name: String,
            serializer: KSerializer<V>,
            fields: Map<String, CampaignField>,
            kind: CampaignPlacementKind = CampaignPlacementKind.MODAL,
        ): CampaignPlacement<V> = CampaignPlacement(name, kind, fields) { values ->
            json.decodeFromJsonElement(serializer, JsonObject(values.mapValues { it.value.toJson() }))
        }

        /** A placement with no look and no values (`review_ask`). */
        public fun system(name: String): CampaignPlacement<Unit> =
            CampaignPlacement(name, CampaignPlacementKind.SYSTEM, emptyMap()) { }
    }
}

public enum class CampaignFactType(public val code: String) {
    BOOL("bool"), INT("int"), NUMBER("number"), STRING("string");

    public companion object {
        public fun of(code: String): CampaignFactType? = entries.firstOrNull { it.code == code }
    }
}

public enum class CampaignFactSource(public val code: String) {
    /** Read on the device. Cheap ones first: conditions are read left to right. */
    DEVICE("device"),
    /** Asked of the app's own server, only after every device condition has passed. */
    SERVER("server"),
    ;

    public companion object {
        public fun of(code: String): CampaignFactSource? = entries.firstOrNull { it.code == code }
    }
}

/** A value about the person that conditions can read. `null` or a thrown error means "not available". */
public class CampaignFact(
    public val name: String,
    public val type: CampaignFactType,
    public val source: CampaignFactSource,
    public val read: suspend () -> CampaignValue?,
) {
    public companion object {
        public fun device(name: String, type: CampaignFactType, read: suspend () -> CampaignValue?): CampaignFact =
            CampaignFact(name, type, CampaignFactSource.DEVICE, read)

        public fun server(name: String, type: CampaignFactType, read: suspend () -> CampaignValue?): CampaignFact =
            CampaignFact(name, type, CampaignFactSource.SERVER, read)
    }
}

public enum class CampaignArgumentType(public val code: String) {
    STRING("string"), INT("int"), NUMBER("number"), BOOL("bool");

    public fun accepts(value: CampaignValue): Boolean = when (this) {
        STRING -> value is CampaignValue.StringValue
        INT -> value is CampaignValue.IntValue
        NUMBER -> value.kind == CampaignValue.Kind.NUMBER
        BOOL -> value is CampaignValue.BoolValue
    }

    public companion object {
        public fun of(code: String): CampaignArgumentType? = entries.firstOrNull { it.code == code }
    }
}

public data class CampaignArgument(val type: CampaignArgumentType, val required: Boolean)

/** What a registered action is handed when it runs. */
public data class CampaignActionRequest(
    val campaign: String,
    val setVersion: Int,
    /** The trigger's id (`after_paywall`), so the action knows where the person came from. */
    val trigger: String,
    val event: ObservedEvent,
    val arguments: Map<String, CampaignValue>,
)

public enum class CampaignActionResult(public val code: String) { COMPLETED("completed"), FAILED("failed"), CANCELLED("cancelled") }

/** An action a campaign's `on_tap` can name. */
public class CampaignAction(
    public val name: String,
    public val arguments: Map<String, CampaignArgument> = emptyMap(),
    public val run: suspend (CampaignActionRequest) -> CampaignActionResult,
) {
    public companion object {
        public const val SUBMIT_ANSWER: String = "submit_answer"

        /**
         * `submit_answer` (survey): the engine already recorded `survey_answered` when the sheet called
         * [CampaignEngine.answer]. Register it so the campaign passes the checks; [sent] runs after.
         * The answer changes nothing the person gets.
         */
        public fun submitAnswer(sent: suspend (CampaignActionRequest) -> Unit = {}): CampaignAction =
            CampaignAction(SUBMIT_ANSWER) { request ->
                sent(request)
                CampaignActionResult.COMPLETED
            }
    }
}

/**
 * Everything the app registered, in the shape the checks read. The engine builds it from what it is
 * given; tests and tools build it from a manifest ([CampaignManifest]).
 */
public data class CampaignRegistry(
    val placements: Map<String, CampaignPlacementSpec>,
    val actions: Map<String, Map<String, CampaignArgument>>,
    val facts: Map<String, FactSpec>,
    val assets: Set<String>,
) {
    public data class FactSpec(val type: CampaignFactType, val source: CampaignFactSource)

    public fun fact(name: String): FactSpec? = facts[name] ?: sdkFacts[name]

    public companion object {
        /** Values the SDK knows without the app. Any audience can use them; the manifest does not list them. */
        public val sdkFacts: Map<String, FactSpec> = mapOf(
            "platform" to FactSpec(CampaignFactType.STRING, CampaignFactSource.DEVICE),
            "app_version" to FactSpec(CampaignFactType.STRING, CampaignFactSource.DEVICE),
            "build" to FactSpec(CampaignFactType.STRING, CampaignFactSource.DEVICE),
            "channel" to FactSpec(CampaignFactType.STRING, CampaignFactSource.DEVICE),
            "lang" to FactSpec(CampaignFactType.STRING, CampaignFactSource.DEVICE),
            "region" to FactSpec(CampaignFactType.STRING, CampaignFactSource.DEVICE),
            "days_since_first_seen" to FactSpec(CampaignFactType.INT, CampaignFactSource.DEVICE),
            "session_seconds" to FactSpec(CampaignFactType.INT, CampaignFactSource.DEVICE),
            "version_age_days" to FactSpec(CampaignFactType.INT, CampaignFactSource.DEVICE),
        )
    }
}

/** The app repo's `campaigns/manifest.json` (`inapp/manifest.schema.json`), read into a registry. */
public object CampaignManifest {
    public fun registry(text: String): CampaignRegistry {
        val root = Json.parseToJsonElement(text).jsonObject
        val placements = root["placements"]?.jsonObject.orEmpty().mapNotNull { (name, value) ->
            val obj = value.jsonObject
            val kind = CampaignPlacementKind.of(obj["kind"]?.jsonPrimitive?.content ?: "") ?: return@mapNotNull null
            val fields = obj["fields"]?.jsonObject.orEmpty().mapValues { (_, field) ->
                val f = field.jsonObject
                CampaignField(
                    type = CampaignFieldType.of(f.getValue("type").jsonPrimitive.content)
                        ?: error("unknown field type in $name"),
                    required = f.getValue("required").jsonPrimitive.booleanOrNull ?: false,
                    maxChars = (f["max_chars"] as? JsonPrimitive)?.intOrNull,
                )
            }
            CampaignPlacementSpec(name, kind, fields)
        }.associateBy { it.name }
        val actions = root["actions"]?.jsonObject.orEmpty().mapValues { (_, value) ->
            value.jsonObject["args"]?.jsonObject.orEmpty().mapValues { (_, arg) ->
                val a = arg.jsonObject
                CampaignArgument(
                    CampaignArgumentType.of(a.getValue("type").jsonPrimitive.content) ?: error("unknown argument type"),
                    a.getValue("required").jsonPrimitive.booleanOrNull ?: false,
                )
            }
        }
        val facts = root["facts"]?.jsonObject.orEmpty().mapValues { (_, value) ->
            val f = value.jsonObject
            CampaignRegistry.FactSpec(
                CampaignFactType.of(f.getValue("type").jsonPrimitive.content) ?: error("unknown fact type"),
                CampaignFactSource.of(f.getValue("source").jsonPrimitive.content) ?: error("unknown fact source"),
            )
        }
        val assets = root["assets"]?.jsonObject.orEmpty().keys
        return CampaignRegistry(placements, actions, facts, assets)
    }

    /**
     * The differences between what the app registered and its manifest; empty when they match. Call
     * it in the app's tests so a campaign check and the device read the same placements and actions.
     */
    public fun verify(registry: CampaignRegistry, manifest: String): List<String> {
        val expected = registry(manifest)
        val problems = mutableListOf<String>()
        fun <T> compare(what: String, actual: Map<String, T>, wanted: Map<String, T>) {
            for (name in wanted.keys - actual.keys) problems += "$what $name is in the manifest but not registered"
            for (name in actual.keys - wanted.keys) problems += "$what $name is registered but not in the manifest"
            for (name in actual.keys intersect wanted.keys) {
                if (actual[name] != wanted[name]) problems += "$what $name differs: registered ${actual[name]}, manifest ${wanted[name]}"
            }
        }
        compare("placement", registry.placements.mapValues { it.value.copy(fields = it.value.fields.mapValues { f -> f.value.copy(maxChars = null) }) },
            expected.placements.mapValues { it.value.copy(fields = it.value.fields.mapValues { f -> f.value.copy(maxChars = null) }) })
        compare("action", registry.actions, expected.actions)
        compare("fact", registry.facts, expected.facts)
        compare("asset", registry.assets.associateWith { true }, expected.assets.associateWith { true })
        return problems
    }
}
