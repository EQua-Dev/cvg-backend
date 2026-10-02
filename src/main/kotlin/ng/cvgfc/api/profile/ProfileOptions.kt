package ng.cvgfc.api.profile

/** Position groups drive the FUT card stats, the questionnaire section and selection. */
enum class PositionGroup { GK, DEF, MID, ATT }

enum class Position(val label: String, val group: PositionGroup, val fullBack: Boolean = false) {
    GK("Goalkeeper", PositionGroup.GK),
    CB("Centre-back", PositionGroup.DEF),
    LB("Left-back", PositionGroup.DEF, fullBack = true),
    RB("Right-back", PositionGroup.DEF, fullBack = true),
    LWB("Left wing-back", PositionGroup.DEF, fullBack = true),
    RWB("Right wing-back", PositionGroup.DEF, fullBack = true),
    CDM("Defensive mid", PositionGroup.MID),
    CM("Central mid", PositionGroup.MID),
    CAM("Attacking mid", PositionGroup.MID),
    LM("Left mid", PositionGroup.MID),
    RM("Right mid", PositionGroup.MID),
    LW("Left wing", PositionGroup.ATT),
    RW("Right wing", PositionGroup.ATT),
    CF("Centre-forward", PositionGroup.ATT),
    ST("Striker", PositionGroup.ATT),
    ;

    companion object {
        fun parse(code: String?): Position? = entries.firstOrNull { it.name == code }
    }
}

enum class Foot { RIGHT, LEFT, BOTH }

/** Pick lists for the profile form. The apps render these as chips, so there is no typing. */
object ProfileOptions {

    /** "List A" in the content plan: used for both strengths and weaknesses. */
    val traits = listOf(
        "Pace", "Finishing", "Long shots", "Passing", "Vision", "Crossing", "Dribbling", "First touch",
        "Composure", "Heading", "Tackling", "Marking", "Positioning", "Stamina", "Strength", "Work rate",
        "Set pieces", "Weak foot", "Communication", "Leadership", "Shot-stopping", "Distribution", "Aerial duels",
    )

    val states = listOf(
        "Abia", "Adamawa", "Akwa Ibom", "Anambra", "Bauchi", "Bayelsa", "Benue", "Borno", "Cross River",
        "Delta", "Ebonyi", "Edo", "Ekiti", "Enugu", "FCT", "Gombe", "Imo", "Jigawa", "Kaduna", "Kano",
        "Katsina", "Kebbi", "Kogi", "Kwara", "Lagos", "Nasarawa", "Niger", "Ogun", "Ondo", "Osun", "Oyo",
        "Plateau", "Rivers", "Sokoto", "Taraba", "Yobe", "Zamfara",
    )

    const val MAX_OTHER_POSITIONS = 3
    const val MAX_TRAITS = 3
}
