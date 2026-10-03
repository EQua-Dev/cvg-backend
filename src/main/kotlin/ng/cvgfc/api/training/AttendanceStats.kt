package ng.cvgfc.api.training

import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.money.CLUB_ZONE
import ng.cvgfc.api.season.SeasonService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

data class MemberAttendance(
    val memberId: UUID,
    val fullName: String,
    val jerseyNumber: Short?,
    /** (present + late) ÷ (compulsory sessions marked − excused). Null until there is one. */
    val percent: Int?,
    val counted: Int,
    val attended: Int,
    val late: Int,
    val excused: Int,
    val absent: Int,
    /** Consecutive compulsory sessions attended, most recent first. Excused doesn't break it. */
    val streak: Int,
    /** Optional sessions attended: a bonus, never counted against anyone. */
    val extras: Int,
    /** Said "in" (or never said out) but didn't turn up. */
    val noShows: Int,
)

data class RecentSession(val sessionId: UUID, val date: LocalDate, val kind: SessionKind, val focus: String?, val venue: String, val mark: Mark)

data class MyAttendance(val stats: MemberAttendance, val recent: List<RecentSession>)

@Service
class AttendanceStats(
    private val sessions: TrainingSessionRepository,
    private val marks: AttendanceMarkRepository,
    private val availability: SessionAvailabilityRepository,
    private val members: MemberRepository,
    private val seasons: SeasonService,
    private val properties: CvgProperties,
) {
    /** Everyone current, best attendance first. Scoped to the active season when there is one. */
    @Transactional(readOnly = true)
    fun squad(): List<MemberAttendance> {
        val data = load()
        return members.findByClubIdOrderByMemberNo(properties.clubId)
            .filter { it.status.isCurrent }
            .map { compute(it.id, it.fullName, it.jerseyNumber, data) }
            .sortedWith(compareByDescending<MemberAttendance> { it.percent ?: -1 }.thenByDescending { it.attended }.thenBy { it.fullName })
    }

    @Transactional(readOnly = true)
    fun forMember(memberId: UUID): MyAttendance {
        val m = members.findById(memberId).orElseThrow()
        val data = load()
        val stats = compute(m.id, m.fullName, m.jerseyNumber, data)
        val recent = data.sessions.asReversed().mapNotNull { s ->
            data.marks[s.id]?.get(memberId)?.let {
                RecentSession(s.id, s.startsAt.atZone(CLUB_ZONE).toLocalDate(), s.kind, s.focus, s.venue, it)
            }
        }.take(12)
        return MyAttendance(stats, recent)
    }

    private class Data(
        val sessions: List<TrainingSession>,
        val marks: Map<UUID, Map<UUID, Mark>>,
        val outs: Set<Pair<UUID, UUID>>,
    )

    private fun load(): Data {
        val season = seasons.current()
        val closed = sessions.findByClubIdAndStatusOrderByStartsAt(properties.clubId, SessionStatus.CLOSED)
            .filter { season == null || it.seasonId == season.id }
        val ids = closed.map { it.id }
        val markMap = marks.findByIdSessionIdIn(ids).groupBy { it.id.sessionId }
            .mapValues { (_, l) -> l.associate { it.id.memberId to it.mark } }
        val outs = availability.findByIdSessionIdIn(ids).filter { it.status == Availability.OUT }
            .map { it.id.sessionId to it.id.memberId }.toSet()
        return Data(closed, markMap, outs)
    }

    private fun compute(memberId: UUID, name: String, jersey: Short?, d: Data): MemberAttendance {
        val compulsory = d.sessions.filter { it.kind == SessionKind.COMPULSORY }
        val myCompulsory = compulsory.mapNotNull { s -> d.marks[s.id]?.get(memberId) }
        val attended = myCompulsory.count { it.attended }
        val excused = myCompulsory.count { it == Mark.EXCUSED }
        val counted = myCompulsory.size - excused
        var streak = 0
        for (mark in myCompulsory.asReversed()) {
            if (mark == Mark.EXCUSED) continue
            if (!mark.attended) break
            streak++
        }
        val extras = d.sessions.filter { it.kind == SessionKind.OPTIONAL }.count { d.marks[it.id]?.get(memberId)?.attended == true }
        val noShows = d.sessions.count { s -> d.marks[s.id]?.get(memberId) == Mark.ABSENT && (s.id to memberId) !in d.outs }
        return MemberAttendance(
            memberId = memberId,
            fullName = name,
            jerseyNumber = jersey,
            percent = if (counted > 0) Math.round(attended * 100.0 / counted).toInt() else null,
            counted = counted,
            attended = attended,
            late = myCompulsory.count { it == Mark.LATE },
            excused = excused,
            absent = myCompulsory.count { it == Mark.ABSENT },
            streak = streak,
            extras = extras,
            noShows = noShows,
        )
    }
}
