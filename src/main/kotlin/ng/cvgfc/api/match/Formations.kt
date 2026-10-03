package ng.cvgfc.api.match

import ng.cvgfc.api.profile.Position

/** A spot on the pitch. x/y are percentages: x left→right, y own goal (100) → their goal (0). */
data class FormationSlot(val idx: Int, val position: Position, val x: Int, val y: Int)

data class Formation(val name: String, val teamSize: Int, val slots: List<FormationSlot>)

/** Formation templates per format. Rows go from the goalkeeper forward, each row left to right. */
object Formations {

    private val templates: Map<Int, List<Pair<String, List<String>>>> = mapOf(
        11 to listOf(
            "4-4-2" to listOf("GK", "LB CB CB RB", "LM CM CM RM", "ST ST"),
            "4-3-3" to listOf("GK", "LB CB CB RB", "CM CDM CM", "LW ST RW"),
            "4-2-3-1" to listOf("GK", "LB CB CB RB", "CDM CDM", "LM CAM RM", "ST"),
            "3-5-2" to listOf("GK", "CB CB CB", "LWB CM CDM CM RWB", "ST ST"),
            "5-3-2" to listOf("GK", "LWB CB CB CB RWB", "CM CM CM", "ST ST"),
        ),
        9 to listOf(
            "3-3-2" to listOf("GK", "LB CB RB", "LM CM RM", "ST ST"),
            "3-2-3" to listOf("GK", "LB CB RB", "CM CM", "LW ST RW"),
        ),
        7 to listOf(
            "2-3-1" to listOf("GK", "CB CB", "LM CM RM", "ST"),
            "3-2-1" to listOf("GK", "LB CB RB", "CM CM", "ST"),
        ),
        5 to listOf(
            "2-1-1" to listOf("GK", "CB CB", "CM", "ST"),
            "1-2-1" to listOf("GK", "CB", "LM RM", "ST"),
        ),
    )

    val all: List<Formation> = templates.flatMap { (size, list) -> list.map { (name, rows) -> build(name, size, rows) } }

    fun forSize(size: Int): List<Formation> = all.filter { it.teamSize == size }

    fun find(size: Int, name: String?): Formation? = all.firstOrNull { it.teamSize == size && it.name == name }

    fun default(size: Int): Formation = forSize(size).first()

    /** Bench spots allowed for each format. */
    fun benchSize(size: Int) = when (size) {
        11 -> 7
        9 -> 5
        7 -> 5
        else -> 3
    }

    private fun build(name: String, size: Int, rows: List<String>): Formation {
        val outfieldRows = rows.size - 1
        var idx = 0
        val slots = rows.flatMapIndexed { r, row ->
            val codes = row.split(" ")
            val y = if (r == 0) 92 else 74 - (r - 1) * (58 / (outfieldRows - 1).coerceAtLeast(1))
            codes.mapIndexed { i, code ->
                FormationSlot(idx++, Position.valueOf(code), x = (i + 1) * 100 / (codes.size + 1), y = y)
            }
        }
        check(slots.size == size) { "$name has ${slots.size} slots, expected $size" }
        return Formation(name, size, slots)
    }
}
