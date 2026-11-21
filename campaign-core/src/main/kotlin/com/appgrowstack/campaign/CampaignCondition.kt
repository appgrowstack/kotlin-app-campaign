package com.appgrowstack.campaign

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A condition: `all`, `any`, or one value compared with one operator. At most 3 groups deep. */
public sealed interface CampaignCondition {
    public data class All(val children: List<CampaignCondition>) : CampaignCondition
    public data class AnyOf(val children: List<CampaignCondition>) : CampaignCondition
    public data class Leaf(val subject: Subject, val operator: Operator) : CampaignCondition

    public sealed interface Subject {
        public val name: String
        public data class Prop(override val name: String) : Subject
        public data class Fact(override val name: String) : Subject
    }

    public sealed interface Operator {
        public data class Eq(val operand: CampaignValue) : Operator
        public data class Ne(val operand: CampaignValue) : Operator
        public data class In(val operands: List<CampaignValue>) : Operator
        public data class NotIn(val operands: List<CampaignValue>) : Operator
        public data class Gte(val operand: Double) : Operator
        public data class Lte(val operand: Double) : Operator
        public data class Exists(val flag: Boolean) : Operator

        /** `null` when the value and the operand are of different kinds (a type mismatch). */
        public fun matches(value: CampaignValue?): Boolean? {
            if (value == null) return if (this is Exists) !flag else false
            return when (this) {
                is Eq -> value.sameAs(operand)
                is Ne -> value.sameAs(operand)?.not()
                is In -> if (operands.first().kind != value.kind) null else operands.any { value.sameAs(it) == true }
                is NotIn -> if (operands.first().kind != value.kind) null else operands.none { value.sameAs(it) == true }
                is Gte -> value.number?.let { it >= operand }
                is Lte -> value.number?.let { it <= operand }
                is Exists -> flag
            }
        }
    }

    public companion object {
        internal const val MAX_DEPTH = 3
        private val operatorNames = setOf("eq", "ne", "in", "not_in", "gte", "lte", "exists")

        internal enum class Kind { PROP, FACT }

        /** Reads a condition, or throws the reason the campaign cannot be used. */
        internal fun parse(json: JsonElement, kind: Kind, registry: CampaignRegistry, depth: Int = 0): CampaignCondition {
            val obj = json as? JsonObject ?: throw CampaignRejection(CampaignRejectReason.INVALID)
            if (obj.isEmpty()) throw CampaignRejection(CampaignRejectReason.INVALID)
            if (obj.size == 1) {
                val (key, value) = obj.entries.first()
                if (key == "all" || key == "any") {
                    val items = value as? JsonArray
                    if (depth >= MAX_DEPTH || items == null || items.isEmpty()) throw CampaignRejection(CampaignRejectReason.INVALID)
                    val children = items.map { parse(it, kind, registry, depth + 1) }
                    return if (key == "all") All(children) else AnyOf(children)
                }
            }
            val subjectKey = if (kind == Kind.PROP) "prop" else "fact"
            val name = (obj[subjectKey] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (obj.size != 2 || name == null || !CampaignNames.isName(name)) throw CampaignRejection(CampaignRejectReason.INVALID)
            val (opName, opValue) = obj.entries.first { it.key != subjectKey }
            if (opName !in operatorNames) throw CampaignRejection(CampaignRejectReason.UNKNOWN_OPERATOR)
            val subject = if (kind == Kind.FACT) {
                if (registry.fact(name) == null) throw CampaignRejection(CampaignRejectReason.UNKNOWN_FACT)
                Subject.Fact(name)
            } else {
                Subject.Prop(name)
            }
            return Leaf(subject, parseOperator(opName, opValue))
        }

        private fun parseOperator(name: String, value: JsonElement): Operator {
            val invalid = CampaignRejection(CampaignRejectReason.INVALID)
            return when (name) {
                "eq", "ne" -> {
                    val scalar = CampaignValue.fromJson(value) ?: throw invalid
                    if (name == "eq") Operator.Eq(scalar) else Operator.Ne(scalar)
                }
                "in", "not_in" -> {
                    val items = value as? JsonArray ?: throw invalid
                    if (items.size !in 1..50) throw invalid
                    val scalars = items.map { CampaignValue.fromJson(it) ?: throw invalid }
                    if (scalars.map { it.kind }.toSet().size != 1) throw invalid
                    if (name == "in") Operator.In(scalars) else Operator.NotIn(scalars)
                }
                "gte", "lte" -> {
                    val number = CampaignValue.fromJson(value)?.number ?: throw invalid
                    if (name == "gte") Operator.Gte(number) else Operator.Lte(number)
                }
                else -> {
                    val flag = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw invalid
                    Operator.Exists(flag)
                }
            }
        }
    }
}

/** The reason a campaign was rejected while reading it. */
internal class CampaignRejection(val reason: CampaignRejectReason) : Exception(reason.code)
