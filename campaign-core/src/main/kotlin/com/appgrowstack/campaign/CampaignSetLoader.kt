package com.appgrowstack.campaign

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads a set and checks each campaign against what the app registered. Pure: no clock, no storage. */
public object CampaignSetLoader {
    /** The schema version this SDK reads. */
    public const val SCHEMA_VERSION: Int = 1

    public data class Rejection(
        /** The campaign's id, `_set` for the whole set, or `_invalid` when the id itself is unreadable. */
        val campaign: String,
        val reason: CampaignRejectReason,
    )

    public sealed interface Result {
        /** The set is usable. [rejections] lists the campaigns that were skipped. */
        public data class Loaded(val set: CampaignSet, val rejections: List<Rejection>) : Result
        /** The whole set is unusable; keep the one on hand. [version] is 0 when unreadable. */
        public data class Unusable(val version: Int, val reason: CampaignRejectReason) : Result
    }

    public fun load(text: String, registry: CampaignRegistry): Result {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Result.Unusable(0, CampaignRejectReason.PARSE_ERROR)
        val version = int(root["version"])
        val schema = int(root["schema_version"]) ?: return Result.Unusable(version ?: 0, CampaignRejectReason.INVALID)
        if (schema > SCHEMA_VERSION) return Result.Unusable(version ?: 0, CampaignRejectReason.SCHEMA_TOO_NEW)
        val project = string(root["project"])
        val items = root["campaigns"] as? JsonArray
        val targetsJson = root["targets"] as? JsonObject
        if (project == null || version == null || version < 0 || items == null || targetsJson == null) {
            return Result.Unusable(version ?: 0, CampaignRejectReason.INVALID)
        }
        val targets = targetsJson.mapNotNull { (id, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val name = string(obj["name"]) ?: return@mapNotNull null
            id to CampaignTarget(
                name = name,
                ascAppId = string((obj["ios"] as? JsonObject)?.get("asc_app_id")),
                playPackage = string((obj["android"] as? JsonObject)?.get("package")),
            )
        }.toMap()

        val campaigns = mutableListOf<Campaign>()
        val rejections = mutableListOf<Rejection>()
        val seen = mutableSetOf<String>()
        for (item in items) {
            try {
                val campaign = parseCampaign(item, registry, targets)
                if (!seen.add(campaign.id)) throw CampaignRejection(CampaignRejectReason.INVALID)
                campaigns.add(campaign)
            } catch (rejection: CampaignRejection) {
                rejections.add(Rejection(rejectedId(item), rejection.reason))
            }
        }
        return Result.Loaded(CampaignSet(project, version, policy(root["policy"]), targets, campaigns), rejections)
    }

    private fun rejectedId(item: JsonElement): String =
        string((item as? JsonObject)?.get("id"))?.takeIf(CampaignNames::isCampaignId) ?: "_invalid"

    private fun policy(json: JsonElement?): CampaignPolicy {
        val obj = json as? JsonObject ?: JsonObject(emptyMap())
        val defaults = CampaignPolicy()
        return CampaignPolicy(
            maxPerSession = int(obj["max_per_session"])?.takeIf { it >= 1 } ?: defaults.maxPerSession,
            maxPerDay = int(obj["max_per_day"])?.takeIf { it >= 1 } ?: defaults.maxPerDay,
            cacheHours = number(obj["cache_hours"])?.takeIf { it > 0 } ?: defaults.cacheHours,
            globalHoldout = (obj["global_holdout"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true",
        )
    }

    // MARK: one campaign

    private fun invalid(): Nothing = throw CampaignRejection(CampaignRejectReason.INVALID)
    private fun reject(reason: CampaignRejectReason): Nothing = throw CampaignRejection(reason)

    private fun parseCampaign(json: JsonElement, registry: CampaignRegistry, targets: Map<String, CampaignTarget>): Campaign {
        val obj = json as? JsonObject ?: invalid()
        val schema = int(obj["schema_version"]) ?: invalid()
        if (schema > SCHEMA_VERSION) reject(CampaignRejectReason.SCHEMA_TOO_NEW)

        val id = string(obj["id"])?.takeIf(CampaignNames::isCampaignId) ?: invalid()
        val type = string(obj["type"])?.let(CampaignType::of) ?: invalid()
        val status = string(obj["status"])?.takeIf { it in setOf("active", "paused", "draft") } ?: invalid()
        val title = string(obj["title"]) ?: invalid()
        string(obj["purpose"]) ?: invalid()
        val priority = int(obj["priority"])?.takeIf { it in 0..1000 } ?: invalid()
        val platforms = (obj["platforms"] as? JsonArray)?.map { string(it) ?: invalid() }?.takeIf { it.isNotEmpty() } ?: invalid()
        val versionObject = (obj["app_version"] as? JsonObject)?.takeIf { it.isNotEmpty() } ?: invalid()
        if ((obj["goals"] as? JsonArray).isNullOrEmpty()) invalid()
        val holdout = number(obj["holdout"])?.takeIf { it in 0.0..0.5 } ?: invalid()

        val appVersion = versionObject.mapValues { (_, value) -> string(value)?.let(VersionRange::parse) ?: invalid() }
        val channels = channelsOf(obj["channels"])
        val startsAt = timestamp(obj, "starts_at")
        val endsAt = timestamp(obj, "ends_at")
        val triggers = triggersOf(obj["triggers"], registry)
        val audience = audienceOf(obj["audience"], registry)
        val limits = limitsOf(obj["limits"], registry)
        val show = showOf(obj["show"], registry)
        val onTap = onTapOf(obj["on_tap"], registry, targets)

        return Campaign(
            id = id, type = type, status = status, title = title, priority = priority, platforms = platforms,
            appVersion = appVersion, channels = channels, startsAt = startsAt, endsAt = endsAt, triggers = triggers,
            audience = audience, limits = limits, holdout = holdout, placement = show.placement, values = show.values,
            textKey = show.textKey, onTap = onTap,
        )
    }

    private fun channelsOf(json: JsonElement?): List<String> {
        if (json == null) return listOf("appstore", "play")
        val items = (json as? JsonArray)?.takeIf { it.isNotEmpty() } ?: invalid()
        return items.map { string(it) ?: invalid() }
    }

    private fun timestamp(obj: JsonObject, key: String): Long? {
        val value = obj[key] ?: invalid()
        if (value is JsonNull) return null
        return string(value)?.let(CampaignTime::parse) ?: invalid()
    }

    private fun triggersOf(json: JsonElement?, registry: CampaignRegistry): List<CampaignTrigger> {
        val items = (json as? JsonArray)?.takeIf { it.isNotEmpty() } ?: invalid()
        return items.map { item ->
            val obj = item as? JsonObject ?: invalid()
            val id = string(obj["id"])?.takeIf(CampaignNames::isName) ?: invalid()
            val event = string(obj["event"])?.takeIf(CampaignNames::isEventName) ?: invalid()
            val wait = int(obj["wait_seconds"])?.takeIf { it in 0..600 } ?: invalid()
            val where = obj["where"] ?: invalid()
            val condition = if (where is JsonNull) null else CampaignCondition.parse(where, CampaignCondition.Companion.Kind.PROP, registry)
            CampaignTrigger(id, event, condition, wait)
        }
    }

    private fun audienceOf(json: JsonElement?, registry: CampaignRegistry): CampaignCondition? {
        if (json == null) invalid()
        if (json is JsonNull) return null
        return CampaignCondition.parse(json, CampaignCondition.Companion.Kind.FACT, registry)
    }

    private fun limitsOf(json: JsonElement?, registry: CampaignRegistry): CampaignLimits {
        val obj = json as? JsonObject ?: invalid()
        val maxShown = int(obj["max_shown"])?.takeIf { it in 1..20 } ?: invalid()
        val perVersion = obj["max_per_app_version"]?.let { int(it)?.takeIf { n -> n in 1..20 } ?: invalid() }
        val between = number(obj["min_hours_between"])?.takeIf { it >= 0 } ?: invalid()
        val dismiss = number(obj["after_dismiss_days"])?.takeIf { it >= 0 } ?: invalid()
        val action = obj["after_action_days"] ?: invalid()
        val afterAction = if (action is JsonNull) null else number(action)?.takeIf { it >= 0 } ?: invalid()
        return CampaignLimits(maxShown, perVersion, between, dismiss, afterAction, launchEventsOf(obj["not_after_in_launch"], registry))
    }

    /** `limits.not_after_in_launch`: none when left out. Each has the trigger's `event` and `where`. */
    private fun launchEventsOf(json: JsonElement?, registry: CampaignRegistry): List<CampaignLaunchEvent> {
        if (json == null) return emptyList()
        val items = (json as? JsonArray)?.takeIf { it.size <= 20 } ?: invalid()
        return items.map { item ->
            val obj = item as? JsonObject ?: invalid()
            val event = string(obj["event"])?.takeIf(CampaignNames::isEventName) ?: invalid()
            val where = obj["where"] ?: invalid()
            val condition = if (where is JsonNull) null else CampaignCondition.parse(where, CampaignCondition.Companion.Kind.PROP, registry)
            CampaignLaunchEvent(event, condition)
        }
    }

    private class Show(val placement: String, val values: Map<String, JsonElement>, val textKey: String?)

    private fun showOf(json: JsonElement?, registry: CampaignRegistry): Show {
        val obj = json as? JsonObject ?: invalid()
        val name = string(obj["placement"]) ?: invalid()
        val values = obj["values"] as? JsonObject ?: invalid()
        val textKey = obj["text_key"]?.let { string(it) ?: invalid() }
        val placement = registry.placements[name] ?: reject(CampaignRejectReason.UNKNOWN_PLACEMENT)
        if (values.keys.any { it !in placement.fields }) reject(CampaignRejectReason.UNKNOWN_VALUE)
        for ((key, field) in placement.fields) {
            val value = values[key]
            if (value == null) {
                // A required text may come from the app's strings table (C5); any other value may not.
                if (field.required && !(field.type == CampaignFieldType.TEXT && textKey != null)) {
                    reject(CampaignRejectReason.MISSING_VALUE)
                }
                continue
            }
            check(value, field.type, registry)
        }
        return Show(name, values, textKey)
    }

    private fun check(value: JsonElement, type: CampaignFieldType, registry: CampaignRegistry) {
        val mismatch = CampaignRejectReason.TYPE_MISMATCH
        when (type) {
            CampaignFieldType.TEXT -> {
                val texts = value as? JsonObject ?: reject(mismatch)
                if (texts.isEmpty() || texts.values.any { string(it).isNullOrEmpty() }) reject(mismatch)
            }
            CampaignFieldType.IMAGE -> {
                val image = CampaignImage.fromJson(value) ?: reject(mismatch)
                if (image is CampaignImage.Asset && image.name !in registry.assets) reject(CampaignRejectReason.UNKNOWN_ASSET)
            }
            CampaignFieldType.INT -> if (CampaignValue.fromJson(value) !is CampaignValue.IntValue) reject(mismatch)
            CampaignFieldType.BOOL -> if (CampaignValue.fromJson(value) !is CampaignValue.BoolValue) reject(mismatch)
            CampaignFieldType.STRING -> if (CampaignValue.fromJson(value) !is CampaignValue.StringValue) reject(mismatch)
        }
    }

    /** The action that opens another product's store page. Its `target` must be one of the set's `targets`. */
    public const val OPEN_STORE_PAGE: String = "open_store_page"

    private fun onTapOf(json: JsonElement?, registry: CampaignRegistry, targets: Map<String, CampaignTarget>): CampaignTap {
        val obj = json as? JsonObject ?: invalid()
        obj["link"]?.let { link ->
            val url = string(link)
            if (obj.size != 1 || url == null || !CampaignNames.isHttpsUrl(url)) invalid()
            return CampaignTap.Link(url)
        }
        val name = string(obj["action"])
        val rawArgs = obj["args"] as? JsonObject
        if (obj.size != 2 || name == null || rawArgs == null) invalid()
        val spec = registry.actions[name] ?: reject(CampaignRejectReason.UNKNOWN_ACTION)
        val args = rawArgs.mapValues { (key, raw) ->
            val argument = spec[key] ?: reject(CampaignRejectReason.UNKNOWN_VALUE)
            val value = CampaignValue.fromJson(raw)
            if (value == null || !argument.type.accepts(value)) reject(CampaignRejectReason.TYPE_MISMATCH)
            value
        }
        if (spec.any { (key, argument) -> argument.required && key !in args }) reject(CampaignRejectReason.MISSING_VALUE)
        if (name == OPEN_STORE_PAGE) {
            val target = (args["target"] as? CampaignValue.StringValue)?.value
            if (target == null || target !in targets) reject(CampaignRejectReason.UNKNOWN_TARGET)
        }
        return CampaignTap.Action(name, args)
    }

    // MARK: JSON scalars

    internal fun string(json: JsonElement?): String? = (json as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun int(json: JsonElement?): Int? =
        (CampaignValue.fromJson(json) as? CampaignValue.IntValue)?.value?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    internal fun number(json: JsonElement?): Double? = CampaignValue.fromJson(json)?.number
}
