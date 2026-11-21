package com.appgrowstack.campaign

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One typed value: an event's prop, a fact, an action's argument (`TypedValue` in the schema).
 * Numbers keep their type: a whole number is [IntValue], any other number is [DoubleValue]. When
 * compared, the two are the same number (`3` equals `3.0`). A string is never read as a number.
 */
public sealed interface CampaignValue {
    public data class StringValue(val value: String) : CampaignValue
    public data class IntValue(val value: Long) : CampaignValue
    public data class DoubleValue(val value: Double) : CampaignValue
    public data class BoolValue(val value: Boolean) : CampaignValue

    public enum class Kind { STRING, NUMBER, BOOL }

    public val kind: Kind
        get() = when (this) {
            is StringValue -> Kind.STRING
            is IntValue, is DoubleValue -> Kind.NUMBER
            is BoolValue -> Kind.BOOL
        }

    /** The number, for `gte` and `lte`; `null` when this is not a number. */
    public val number: Double?
        get() = when (this) {
            is IntValue -> value.toDouble()
            is DoubleValue -> value
            else -> null
        }

    /** `null` when the two are of different kinds (a type mismatch). */
    public fun sameAs(other: CampaignValue): Boolean? {
        if (kind != other.kind) return null
        return when {
            this is IntValue && other is IntValue -> value == other.value
            kind == Kind.NUMBER -> number == other.number
            else -> this == other
        }
    }

    public fun toJson(): JsonPrimitive = when (this) {
        is StringValue -> JsonPrimitive(value)
        is IntValue -> JsonPrimitive(value)
        is DoubleValue -> JsonPrimitive(value)
        is BoolValue -> JsonPrimitive(value)
    }

    public companion object {
        public fun of(value: String): CampaignValue = StringValue(value)
        public fun of(value: Int): CampaignValue = IntValue(value.toLong())
        public fun of(value: Long): CampaignValue = IntValue(value)
        public fun of(value: Double): CampaignValue = DoubleValue(value)
        public fun of(value: Boolean): CampaignValue = BoolValue(value)

        private val wholeNumber = Regex("^-?\\d+$")

        /** A JSON scalar as a value; `null` for null, objects and arrays. */
        public fun fromJson(json: JsonElement?): CampaignValue? {
            if (json !is JsonPrimitive || json is JsonNull) return null
            if (json.isString) return StringValue(json.content)
            json.booleanOrNull?.let { return BoolValue(it) }
            if (wholeNumber.matches(json.content)) json.longOrNull?.let { return IntValue(it) }
            return json.doubleOrNull?.let { DoubleValue(it) }
        }
    }
}
