package com.appgrowstack.campaign

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.OffsetDateTime

/** An event the app's analytics also receives (`inapp/event.schema.json`). Props keep their types. */
public data class ObservedEvent(val name: String, val props: Map<String, CampaignValue> = emptyMap())

/** An image a campaign names: one bundled in the app, or an HTTPS image fetched ahead of time. */
@Serializable(with = CampaignImage.Serializer::class)
public sealed interface CampaignImage {
    public data class Asset(val name: String) : CampaignImage
    public data class Url(val url: String, val width: Int, val height: Int) : CampaignImage

    public fun toJson(): JsonObject = when (this) {
        is Asset -> JsonObject(mapOf("asset" to JsonPrimitive(name)))
        is Url -> JsonObject(mapOf("url" to JsonPrimitive(url), "width" to JsonPrimitive(width), "height" to JsonPrimitive(height)))
    }

    public companion object {
        public fun fromJson(json: JsonElement?): CampaignImage? {
            val obj = json as? JsonObject ?: return null
            if (obj.keys == setOf("asset")) {
                val name = (obj["asset"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                return if (CampaignNames.isName(name)) Asset(name) else null
            }
            if (obj.keys != setOf("url", "width", "height")) return null
            val url = (obj["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val width = (obj["width"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
            val height = (obj["height"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
            if (!CampaignNames.isHttpsUrl(url) || width < 1 || height < 1) return null
            return Url(url, width, height)
        }
    }

    public object Serializer : KSerializer<CampaignImage> {
        override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
        override fun serialize(encoder: Encoder, value: CampaignImage) {
            (encoder as JsonEncoder).encodeJsonElement(value.toJson())
        }
        override fun deserialize(decoder: Decoder): CampaignImage {
            val element = (decoder as JsonDecoder).decodeJsonElement()
            return fromJson(element) ?: throw IllegalArgumentException("not a campaign image: $element")
        }
    }
}

/** A value handed to a placement, after the language is picked. */
public sealed interface CampaignResolvedValue {
    public data class Text(val value: String) : CampaignResolvedValue
    public data class Image(val value: CampaignImage) : CampaignResolvedValue
    public data class IntValue(val value: Long) : CampaignResolvedValue
    public data class BoolValue(val value: Boolean) : CampaignResolvedValue
    public data class StringValue(val value: String) : CampaignResolvedValue

    public fun toJson(): JsonElement = when (this) {
        is Text -> JsonPrimitive(value)
        is Image -> value.toJson()
        is IntValue -> JsonPrimitive(value)
        is BoolValue -> JsonPrimitive(value)
        is StringValue -> JsonPrimitive(value)
    }
}

public enum class CampaignType(public val code: String) {
    TRIAL_OFFER("trial_offer"), REVIEW_ASK("review_ask"), WHATS_NEW("whats_new"), CROSS_PROMO("cross_promo"), SURVEY("survey"), CUSTOM("custom");

    public companion object {
        public fun of(code: String): CampaignType? = entries.firstOrNull { it.code == code }
    }
}

public data class CampaignTrigger(
    val id: String,
    val event: String,
    val condition: CampaignCondition?,
    val waitSeconds: Int,
)

public data class CampaignLimits(
    val maxShown: Int,
    val maxPerAppVersion: Int?,
    val minHoursBetween: Double,
    val afterDismissDays: Double,
    /** `null`: never again after the action completed. */
    val afterActionDays: Double?,
    /** Not shown after any of these was heard earlier in this launch (`not_after_in_launch`). */
    val notAfterInLaunch: List<CampaignLaunchEvent> = emptyList(),
)

/** The shape of an event heard earlier in this launch: the name, and `where` on its props. */
public data class CampaignLaunchEvent(
    val event: String,
    val condition: CampaignCondition?,
)

public sealed interface CampaignTap {
    public data class Action(val name: String, val arguments: Map<String, CampaignValue>) : CampaignTap
    public data class Link(val url: String) : CampaignTap
}

/** One campaign as read from a set (`inapp/campaign.schema.json`). Times are epoch milliseconds. */
public data class Campaign(
    val id: String,
    val type: CampaignType,
    val status: String,
    val title: String,
    val priority: Int,
    val platforms: List<String>,
    val appVersion: Map<String, VersionRange>,
    val channels: List<String>,
    val startsAt: Long?,
    val endsAt: Long?,
    val triggers: List<CampaignTrigger>,
    val audience: CampaignCondition?,
    val limits: CampaignLimits,
    val holdout: Double,
    val placement: String,
    /** The values as written: text per language, images, scalars. Resolved when chosen. */
    val values: Map<String, JsonElement>,
    val textKey: String?,
    val onTap: CampaignTap,
    /** The campaign's two variants (R10 P5), or `null` when it has none. The shown values lay the chosen variant's values over [values]. */
    val variants: List<CampaignVariant>? = null,
)

/** One of a campaign's variants (R10 P5). [values] are laid over the campaign's own values, and [textKey] (when set) replaces the campaign's. */
public data class CampaignVariant(
    val id: String,
    val values: Map<String, JsonElement>,
    val textKey: String?,
)

/**
 * How a campaign with variants splits the shown side between them (the set's `allocations`). [weights]
 * follow the order of the variants; a weight of 0 is a stopped variant. [policyVersion] is sent as
 * `policy_version` on `campaign_eligible`.
 */
public data class CampaignAllocation(val weights: List<Int>, val policyVersion: Int)

/** The products' shared rules. [globalHoldout] applies the product-wide holdout (R10): a person in it is shown no campaign. */
public data class CampaignPolicy(val maxPerSession: Int = 1, val maxPerDay: Int = 1, val cacheHours: Double = 72.0, val globalHoldout: Boolean = false)

/** A product a cross_promo campaign points to (the set's `targets`). */
public data class CampaignTarget(val name: String, val ascAppId: String?, val playPackage: String?)

public data class CampaignSet(
    val project: String,
    val version: Int,
    val policy: CampaignPolicy,
    val targets: Map<String, CampaignTarget>,
    val campaigns: List<Campaign>,
    /** Campaign id → its allocation. Only readable ones; a campaign with variants and no entry splits evenly. */
    val allocations: Map<String, CampaignAllocation> = emptyMap(),
)

/** What the device knows about itself. The same values the manifest calls SDK facts. */
public data class CampaignContext(
    val appVersion: String,
    val build: String,
    /** `play`, `play_test` or `debug` on Android. Campaigns without `channels` show on `appstore` and `play` only. */
    val channel: String,
    val lang: String,
    val region: String,
    val timeZone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    /** The owner's or a tester's device: always on the treatment side. Set by the app's debug screen. */
    val internalDevice: Boolean = false,
    val platform: String = "android",
    /**
     * When the person first used the app (epoch milliseconds), if the app knew it before this SDK (its
     * own first-launch date). `days_since_first_seen` counts from the earlier of this and the date the
     * engine stored on its own first launch. `null`: the engine's own date only.
     */
    val firstSeenAt: Long? = null,
)

internal object CampaignNames {
    private val name = Regex("^[a-z0-9_]{1,40}$")
    private val campaignId = Regex("^[a-z0-9-]{3,40}$")
    private val eventName = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val httpsUrl = Regex("^https://[^\\s]+$")

    fun isName(text: String) = name.matches(text)
    fun isCampaignId(text: String) = campaignId.matches(text)
    fun isEventName(text: String) = eventName.matches(text)
    fun isHttpsUrl(text: String) = text.length <= 2048 && httpsUrl.matches(text)
}

/** ISO 8601 with an offset (`2026-12-10T12:00:00+09:00`) as epoch milliseconds. */
public object CampaignTime {
    public fun parse(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
