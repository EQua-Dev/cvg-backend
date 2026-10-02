package ng.cvgfc.api.profiling

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ng.cvgfc.api.profile.Position
import ng.cvgfc.api.profile.PositionGroup
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import kotlin.math.roundToInt

// The questionnaire is data (resources/profiling/questionnaire-v1.json), transcribed from
// docs/PROFILING_QUESTIONNAIRE.md. Points are never sent to the apps.

data class QOption(val id: String, val text: String, val points: Map<String, Int> = emptyMap())

data class QQuestion(
    val id: String,
    val text: String,
    val options: List<QOption>,
    val scored: Boolean = true,
    /** "FB" = full-backs/wing-backs only, "CB" = centre-backs only. */
    val only: String? = null,
)

data class QPlan(val code: String, val name: String, val adjective: String)

data class QRole(val code: String, val name: String)

data class QIntro(val title: String, val subtitle: String)

data class QuestionnaireDef(
    val version: Int,
    val intro: QIntro,
    val plans: List<QPlan>,
    val roles: Map<String, List<QRole>>,
    val consistencyCheck: List<String>,
    val sections: Map<String, List<QQuestion>>,
)

data class RoleResult(val code: String, val name: String)

data class ProfilingResult(
    val version: Int,
    val group: PositionGroup,
    /** Game plan code → fit 0–100, in plan order. */
    val planFits: Map<String, Int>,
    val topPlan: String,
    val mainRole: RoleResult?,
    val secondaryRole: RoleResult?,
    /** e.g. "Counter-attacking Inside Forward". */
    val label: String,
    /** The two consistency questions point in unrelated directions. */
    val lowConfidence: Boolean,
    /** Unscored "best style for CVG?" answer, for the coach. */
    val coachPick: String?,
)

@Component
class Questionnaire(mapper: ObjectMapper) {

    val def: QuestionnaireDef = ClassPathResource("profiling/questionnaire-v1.json").inputStream.use { mapper.readValue(it) }

    /** The 15 questions a player sees: the common section plus their position group's section. */
    fun questionsFor(position: Position): List<QQuestion> {
        val variant = if (position.fullBack) "FB" else "CB"
        val section = def.sections.getValue(position.group.name).filter { it.only == null || it.only == variant }
        return def.sections.getValue("COMMON") + section
    }

    /** Validates a full set of answers and scores it. Throws [IllegalArgumentException] with a short message. */
    fun score(position: Position, answers: Map<String, String>): ProfilingResult {
        val questions = questionsFor(position)
        val chosen = questions.associate { q ->
            val optionId = answers[q.id]
            val option = q.options.firstOrNull { it.id == optionId }
            require(option != null || !q.scored) { "Answer every question." }
            q.id to option
        }
        val unknown = answers.keys - questions.map { it.id }.toSet()
        require(unknown.isEmpty()) { "Unknown question." }

        val scored = questions.filter { it.scored }
        val planFits = def.plans.associate { plan ->
            val earned = scored.sumOf { chosen[it.id]?.points?.get(plan.code) ?: 0 }
            val possible = scored.sumOf { q -> q.options.maxOf { it.points[plan.code] ?: 0 } }
            plan.code to if (possible == 0) 0 else (earned * 100.0 / possible).roundToInt()
        }
        // Ties go to the plan listed first, which keeps results stable.
        val topPlan = def.plans.maxBy { planFits.getValue(it.code) }

        val roles = def.roles.getValue(position.group.name)
        val roleScores = roles.associateWith { r -> scored.sumOf { chosen[it.id]?.points?.get(r.code) ?: 0 } }
        val ranked = roles.sortedByDescending { roleScores.getValue(it) }
        val main = ranked.first().takeIf { roleScores.getValue(it) > 0 }
        val second = ranked.getOrNull(1)?.takeIf {
            main != null && roleScores.getValue(it) > 0 && roleScores.getValue(it) >= 0.8 * roleScores.getValue(main)
        }

        val (q1, q2) = def.consistencyCheck
        val plansOf = { id: String -> chosen[id]?.points?.keys.orEmpty().intersect(def.plans.map { it.code }.toSet()) }
        val lowConfidence = plansOf(q1).isNotEmpty() && plansOf(q2).isNotEmpty() && plansOf(q1).intersect(plansOf(q2)).isEmpty()

        val coachPick = questions.firstOrNull { !it.scored }?.let { chosen[it.id]?.text }

        return ProfilingResult(
            version = def.version,
            group = position.group,
            planFits = planFits,
            topPlan = topPlan.code,
            mainRole = main?.let { RoleResult(it.code, it.name) },
            secondaryRole = second?.let { RoleResult(it.code, it.name) },
            label = listOfNotNull(topPlan.adjective, main?.name ?: position.label).joinToString(" "),
            lowConfidence = lowConfidence,
            coachPick = coachPick,
        )
    }
}
