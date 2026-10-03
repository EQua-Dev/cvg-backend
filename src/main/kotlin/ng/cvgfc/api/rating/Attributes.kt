package ng.cvgfc.api.rating

import ng.cvgfc.api.profile.PositionGroup
import kotlin.math.floor
import kotlin.math.roundToInt

enum class Block(val label: String) { GOALKEEPING("Goalkeeping"), DEFENDING("Defending"), MIDFIELD("Midfield"), ATTACKING("Attacking") }

/** The 20 attributes everyone is rated on. [label] is what the card shows. */
enum class Attribute(val label: String, val title: String, val block: Block) {
    REF("REF", "Reflexes", Block.GOALKEEPING),
    HAN("HAN", "Handling", Block.GOALKEEPING),
    GPO("POS", "Positioning (in goal)", Block.GOALKEEPING),
    DIS("DIS", "Distribution", Block.GOALKEEPING),
    COM("COM", "Command of area", Block.GOALKEEPING),
    OVO("1V1", "One-on-ones", Block.GOALKEEPING),
    TAC("TAC", "Tackling", Block.DEFENDING),
    MRK("MRK", "Marking", Block.DEFENDING),
    AER("AER", "Aerial", Block.DEFENDING),
    STR("STR", "Strength", Block.DEFENDING),
    PAS("PAS", "Passing", Block.MIDFIELD),
    VIS("VIS", "Vision", Block.MIDFIELD),
    CTL("CTL", "Ball control", Block.MIDFIELD),
    STA("STA", "Stamina", Block.MIDFIELD),
    PAC("PAC", "Pace", Block.ATTACKING),
    DRI("DRI", "Dribbling", Block.ATTACKING),
    FIN("FIN", "Finishing", Block.ATTACKING),
    MOV("MOV", "Off-ball movement", Block.ATTACKING),
    CMP("CMP", "Composure", Block.ATTACKING),
    SHO("SHO", "Shot power", Block.ATTACKING),
    ;

    companion object {
        fun parse(code: String): Attribute? = entries.firstOrNull { it.name == code }
    }
}

/** Which rating block a player's own group sees first. */
fun PositionGroup.block() = when (this) {
    PositionGroup.GK -> Block.GOALKEEPING
    PositionGroup.DEF -> Block.DEFENDING
    PositionGroup.MID -> Block.MIDFIELD
    PositionGroup.ATT -> Block.ATTACKING
}

enum class Tier(val label: String) { BRONZE("Bronze"), SILVER("Silver"), GOLD("Gold"), ELITE("CVG Elite") }

/** The card rules from the content plan, kept pure so they're easy to test. */
object CardMath {
    /** Taps on the 5-step scale: Weak, Fair, Good, Strong, Elite. */
    val SCALE = setOf(2, 4, 6, 8, 10)
    const val MIN_PEERS_TO_PUBLISH = 5
    private const val TRIM_FROM = 8

    /** With 8 or more peer scores, drop the top and bottom 10% (at least one each). */
    fun trim(peers: List<Int>): List<Int> {
        if (peers.size < TRIM_FROM) return peers
        val k = maxOf(1, floor(peers.size * 0.1).toInt())
        return peers.sorted().drop(k).dropLast(k)
    }

    /** Peer average with the player's own score at half weight; null with no peer scores. Scale 2–10. */
    fun stat(peers: List<Int>, self: Int?): Double? {
        if (peers.isEmpty()) return null
        val kept = trim(peers)
        val selfWeight = if (self != null) 0.5 else 0.0
        return (kept.sum() + selfWeight * (self ?: 0)) / (kept.size + selfWeight)
    }

    fun toCard(stat: Double): Int = (stat * 10).roundToInt().coerceIn(30, 99)

    fun ovr(values: List<Int?>): Int? = if (values.isEmpty() || values.any { it == null }) null else values.filterNotNull().average().roundToInt()

    fun tier(ovr: Int): Tier = when {
        ovr >= 85 -> Tier.ELITE
        ovr >= 75 -> Tier.GOLD
        ovr >= 65 -> Tier.SILVER
        else -> Tier.BRONZE
    }
}
