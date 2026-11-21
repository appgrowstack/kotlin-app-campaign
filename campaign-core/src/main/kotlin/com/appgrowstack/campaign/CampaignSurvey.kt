package com.appgrowstack.campaign

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `survey` type (sheet): one question with 2–6 choices (or a 0–`scale` row whose ends are `choice_1`
 * and `choice_2`), and an optional text box. The same as `schema/inapp/templates/survey.json`.
 *
 * ```kotlin
 * CampaignEngine(…, placements = listOf(CampaignSurvey.placement), actions = listOf(CampaignAction.submitAnswer(), …))
 * // the sheet: offer.answer(choice.id); then, if something was written, send it as feedback
 * ```
 *
 * Answers never unlock anything.
 */
public object CampaignSurvey {
    public const val NAME: String = "survey"

    public val fields: Map<String, CampaignField> = mapOf(
        "question" to CampaignField.text(maxChars = 60),
        "choice_1" to CampaignField.text(maxChars = 24),
        "choice_2" to CampaignField.text(maxChars = 24),
        "choice_3" to CampaignField.text(required = false, maxChars = 24),
        "choice_4" to CampaignField.text(required = false, maxChars = 24),
        "choice_5" to CampaignField.text(required = false, maxChars = 24),
        "choice_6" to CampaignField.text(required = false, maxChars = 24),
        "scale" to CampaignField.int(),
        "free_text" to CampaignField.bool(),
        "free_text_hint" to CampaignField.text(required = false, maxChars = 40),
    )

    @Serializable
    public data class Values(
        val question: String,
        @SerialName("choice_1") val choice1: String,
        @SerialName("choice_2") val choice2: String,
        @SerialName("choice_3") val choice3: String? = null,
        @SerialName("choice_4") val choice4: String? = null,
        @SerialName("choice_5") val choice5: String? = null,
        @SerialName("choice_6") val choice6: String? = null,
        /** Answers are 0..scale (NPS: 10). [choice1] and [choice2] label the low and the high end. */
        val scale: Int? = null,
        @SerialName("free_text") val freeText: Boolean? = null,
        @SerialName("free_text_hint") val freeTextHint: String? = null,
    ) {
        /** One answer: the id to hand to `answer` and its label (for a scale, only the two ends have one). */
        public data class Choice(val id: String, val label: String?)

        /** The choices in order. */
        public val choices: List<Choice>
            get() {
                val s = scale
                if (s != null) return (0..maxOf(0, s)).map { n -> Choice(n.toString(), if (n == 0) choice1 else if (n == s) choice2 else null) }
                return listOf(choice1, choice2, choice3, choice4, choice5, choice6)
                    .mapIndexedNotNull { index, label -> label?.let { Choice("choice_${index + 1}", it) } }
            }

        /** Whether to show the text box. */
        public val asksForText: Boolean get() = freeText == true
    }

    /** The placement to register. */
    public val placement: CampaignPlacement<Values> =
        CampaignPlacement.of(NAME, Values.serializer(), fields, CampaignPlacementKind.SHEET)

    /** Whether [choice] is one of the campaign's answers. */
    public fun isChoice(choice: String, values: Map<String, CampaignResolvedValue>): Boolean {
        val scale = values["scale"]
        if (scale is CampaignResolvedValue.IntValue) {
            val n = choice.toIntOrNull() ?: return false
            return n.toString() == choice && n in 0..maxOf(0L, scale.value).toInt()
        }
        if (!choice.startsWith("choice_")) return false
        val n = choice.removePrefix("choice_").toIntOrNull() ?: return false
        return n in 1..6 && values.containsKey(choice)
    }
}
