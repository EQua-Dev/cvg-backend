package ng.cvgfc.api.training

import ng.cvgfc.api.audit.AuditService
import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.member.Role
import ng.cvgfc.api.money.CLUB_ZONE
import ng.cvgfc.api.season.SeasonService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

data class PatternView(val id: UUID, val weekday: Int, val startTime: LocalTime, val venue: String, val kind: SessionKind)

data class PatternRequest(val weekday: Int, val startTime: LocalTime, val venue: String, val kind: SessionKind = SessionKind.COMPULSORY)

data class ImpromptuRequest(
    val date: LocalDate,
    val startTime: LocalTime,
    val venue: String,
    val kind: SessionKind = SessionKind.OPTIONAL,
    val focus: String? = null,
)

data class FocusRequest(val focus: String?)

data class MyAvailability(val status: Availability, val reason: OutReason?, val locked: Boolean, val lockAt: Instant)

data class SessionView(
    val id: UUID,
    val startsAt: Instant,
    val date: LocalDate,
    val time: LocalTime,
    val venue: String,
    val kind: SessionKind,
    val impromptu: Boolean,
    val focus: String?,
    val status: SessionStatus,
    val inCount: Int,
    val outCount: Int,
    val markedCount: Int,
    val me: MyAvailability?,
    val myMark: Mark?,
)

data class RosterRow(
    val memberId: UUID,
    val fullName: String,
    val nickname: String?,
    val jerseyNumber: Short?,
    val availability: Availability,
    val reason: OutReason?,
    val mark: Mark?,
)

data class SessionDetail(val session: SessionView, val roster: List<RosterRow>?)

data class AvailabilityRequest(val status: Availability, val reason: OutReason? = null)

data class MarkChange(val memberId: UUID, val mark: Mark?, val markedAt: Instant? = null)

data class MarksRequest(val marks: List<MarkChange>)

@Service
class TrainingService(
    private val patterns: TrainingPatternRepository,
    private val sessions: TrainingSessionRepository,
    private val availability: SessionAvailabilityRepository,
    private val marks: AttendanceMarkRepository,
    private val members: MemberRepository,
    private val seasons: SeasonService,
    private val audit: AuditService,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val clubId get() = properties.clubId
    private val config get() = properties.training
    private fun now() = Instant.now(clock)
    private fun today() = LocalDate.now(clock.withZone(CLUB_ZONE))
    private fun localDate(i: Instant) = i.atZone(CLUB_ZONE).toLocalDate()

    // ---------- schedule ----------

    @Transactional(readOnly = true)
    fun patterns(): List<PatternView> =
        patterns.findByClubIdAndActiveTrueOrderByWeekdayAscStartTimeAsc(clubId).map { it.view() }

    @Transactional
    fun addPattern(req: PatternRequest, actorId: UUID): PatternView {
        if (req.weekday !in 1..7) throw ApiException.badRequest("invalid_day", "Pick a day.")
        val venue = req.venue.trim().takeIf { it.isNotEmpty() }?.take(80) ?: throw ApiException.badRequest("venue_required", "Where?")
        val p = patterns.save(TrainingPattern(clubId, req.weekday.toShort(), req.startTime.withSecond(0).withNano(0), venue, req.kind, actorId))
        audit.record(actorId, "training.pattern_added", "training_pattern", p.id, "${p.dayOfWeek} ${p.startTime} ${p.venue} (${p.kind})")
        generateUpcoming()
        return p.view()
    }

    /** Stops a weekly slot. Its future sessions go too, unless someone was already marked. */
    @Transactional
    fun removePattern(id: UUID, actorId: UUID) {
        val p = patterns.findByIdAndClubId(id, clubId) ?: throw ApiException.notFound("Schedule")
        p.active = false
        val future = sessions.findByPatternIdAndStartsAtAfter(p.id, now())
        val marked = marks.findByIdSessionIdIn(future.map { it.id }).map { it.id.sessionId }.toSet()
        sessions.deleteAll(future.filter { it.id !in marked && it.status == SessionStatus.SCHEDULED })
        audit.record(actorId, "training.pattern_removed", "training_pattern", p.id, "${p.dayOfWeek} ${p.startTime} ${p.venue}")
    }

    /** Creates sessions for the coming days from the weekly schedule. Safe to run repeatedly. */
    @Transactional
    fun generateUpcoming(): Int = generateUpcoming(today())

    // No default argument here: Kotlin evaluates defaults on the Spring proxy, where fields are null.
    @Transactional
    fun generateUpcoming(today: LocalDate): Int {
        val season = seasons.current()
        var created = 0
        for (p in patterns.findByClubIdAndActiveTrueOrderByWeekdayAscStartTimeAsc(clubId)) {
            for (offset in 0..config.daysAhead.toLong()) {
                val day = today.plusDays(offset)
                if (day.dayOfWeek != p.dayOfWeek) continue
                val startsAt = ZonedDateTime.of(day, p.startTime, CLUB_ZONE).toInstant()
                if (sessions.existsByPatternIdAndStartsAt(p.id, startsAt)) continue
                sessions.save(TrainingSession(clubId, season?.id, p.id, startsAt, p.venue, p.kind, impromptu = false, createdBy = null))
                created++
            }
        }
        if (created > 0) log.info("Generated {} training session(s)", created)
        return created
    }

    @Transactional
    fun addImpromptu(req: ImpromptuRequest, actorId: UUID): SessionView {
        if (req.date.isBefore(today())) throw ApiException.badRequest("past_date", "Pick today or later.")
        val venue = req.venue.trim().takeIf { it.isNotEmpty() }?.take(80) ?: throw ApiException.badRequest("venue_required", "Where?")
        val s = sessions.save(
            TrainingSession(clubId, seasons.current()?.id, null, ZonedDateTime.of(req.date, req.startTime, CLUB_ZONE).toInstant(),
                venue, req.kind, impromptu = true, createdBy = actorId),
        ).apply { focus = req.focus?.trim()?.takeIf { it.isNotEmpty() }?.take(120) }
        audit.record(actorId, "training.impromptu_added", "training_session", s.id, "Impromptu ${req.date} ${req.startTime} ${s.venue}")
        return views(listOf(s), null).first()
    }

    @Transactional
    fun setFocus(id: UUID, focus: String?, actorId: UUID): SessionView {
        val s = get(id)
        s.focus = focus?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
        return views(listOf(s), null).first()
    }

    @Transactional
    fun cancel(id: UUID, actorId: UUID): SessionView {
        val s = get(id)
        if (s.status != SessionStatus.SCHEDULED) throw ApiException.conflict("not_scheduled", "Only upcoming sessions can be cancelled.")
        s.status = SessionStatus.CANCELLED
        audit.record(actorId, "training.cancelled", "training_session", s.id, "Cancelled ${localDate(s.startsAt)} ${s.venue}")
        return views(listOf(s), null).first()
    }

    // ---------- reading ----------

    @Transactional(readOnly = true)
    fun list(from: LocalDate?, to: LocalDate?, me: CurrentMember): List<SessionView> {
        val start = (from ?: today()).atStartOfDay(CLUB_ZONE).toInstant()
        val end = (to ?: today().plusDays(config.daysAhead.toLong())).plusDays(1).atStartOfDay(CLUB_ZONE).toInstant()
        return views(sessions.findByClubIdAndStartsAtBetweenOrderByStartsAt(clubId, start, end), me.id)
    }

    @Transactional(readOnly = true)
    fun detail(id: UUID, me: CurrentMember): SessionDetail {
        val s = get(id)
        val view = views(listOf(s), me.id).first()
        val canSeeRoster = me.has(Role.ADMIN) || me.has(Role.COACH) || me.has(Role.CAPTAIN)
        return SessionDetail(view, if (canSeeRoster) roster(s) else null)
    }

    // ---------- availability ----------

    /** A member says in or out for themself, up to the cutoff before the start. */
    @Transactional
    fun setMyAvailability(id: UUID, req: AvailabilityRequest, me: CurrentMember): SessionView {
        val s = get(id)
        if (s.status != SessionStatus.SCHEDULED) throw ApiException.conflict("not_scheduled", "This session is not open.")
        if (!now().isBefore(lockAt(s))) {
            throw ApiException(HttpStatus.CONFLICT, "availability_locked", "Too late to change. Tell the coach.")
        }
        save(s, me.id, req, me.id)
        return views(listOf(s), me.id).first()
    }

    /** Coach, captain or admin changes someone's availability at any time before the session closes. */
    @Transactional
    fun setAvailabilityFor(id: UUID, memberId: UUID, req: AvailabilityRequest, actorId: UUID): SessionDetail {
        val s = get(id)
        if (s.status != SessionStatus.SCHEDULED) throw ApiException.conflict("not_scheduled", "This session is not open.")
        val m = members.findByIdAndClubId(memberId, clubId) ?: throw ApiException.notFound("Member")
        save(s, m.id, req, actorId)
        audit.record(actorId, "training.availability_set", "training_session", s.id,
            "${m.fullName}: ${req.status}${req.reason?.let { " ($it)" } ?: ""}")
        return SessionDetail(views(listOf(s), null).first(), roster(s))
    }

    private fun save(s: TrainingSession, memberId: UUID, req: AvailabilityRequest, actorId: UUID) {
        if (req.status == Availability.OUT && req.reason == null) throw ApiException.badRequest("reason_required", "Why are you out?")
        val key = SessionMemberId(s.id, memberId)
        val row = availability.findById(key).orElse(null)
        if (row == null) {
            availability.save(SessionAvailability(key, req.status, req.reason.takeIf { req.status == Availability.OUT }, actorId, now()))
        } else {
            row.status = req.status
            row.reason = req.reason.takeIf { req.status == Availability.OUT }
            row.setBy = actorId
            row.setAt = now()
        }
    }

    // ---------- attendance ----------

    /**
     * Pitchside marks, possibly synced late from an offline phone. For each member the newest tap
     * (by when it happened on the phone) wins. After a session is closed only coach/admin can
     * change marks, and each change is audited.
     */
    @Transactional
    fun mark(id: UUID, req: MarksRequest, me: CurrentMember): SessionDetail {
        val s = get(id)
        if (s.status == SessionStatus.CANCELLED) throw ApiException.conflict("cancelled", "This session was cancelled.")
        if (localDate(s.startsAt).isAfter(today())) throw ApiException.conflict("too_early", "You can mark on the day.")
        val afterClose = s.status == SessionStatus.CLOSED
        if (afterClose && !(me.has(Role.ADMIN) || me.has(Role.COACH))) {
            throw ApiException(HttpStatus.FORBIDDEN, "closed", "Session closed. Ask the coach to change it.")
        }
        val known = members.findAllById(req.marks.map { it.memberId }.toSet()).filter { it.clubId == clubId }.associateBy { it.id }
        val existing = marks.findByIdSessionId(s.id).associateBy { it.id.memberId }
        val now = now()
        for (change in req.marks.sortedBy { it.markedAt ?: now }) {
            val member = known[change.memberId] ?: throw ApiException.notFound("Member")
            val at = (change.markedAt ?: now).coerceAtMost(now)
            val current = existing[member.id]
            if (current != null && current.clientMarkedAt.isAfter(at)) continue // an older tap arriving late
            val before = current?.mark
            when {
                change.mark == null && current != null -> marks.delete(current)
                change.mark == null -> {}
                current == null -> marks.save(AttendanceMark(SessionMemberId(s.id, member.id), change.mark, me.id, at, now))
                else -> current.apply { mark = change.mark; markedBy = me.id; clientMarkedAt = at; syncedAt = now }
            }
            if (afterClose && before != change.mark) {
                audit.record(me.id, "attendance.changed_after_close", "training_session", s.id,
                    "${member.fullName}: ${before ?: "—"} → ${change.mark ?: "—"} (${localDate(s.startsAt)})",
                    before = mapOf("mark" to before?.name), after = mapOf("mark" to change.mark?.name))
            }
        }
        marks.flush()
        return SessionDetail(views(listOf(s), me.id).first(), roster(s))
    }

    /** Locks the session. Anyone not marked is recorded absent. */
    @Transactional
    fun close(id: UUID, actorId: UUID): SessionDetail {
        val s = get(id)
        if (s.status != SessionStatus.SCHEDULED) throw ApiException.conflict("not_scheduled", "This session is not open.")
        if (localDate(s.startsAt).isAfter(today())) throw ApiException.conflict("too_early", "You can close it on the day.")
        val marked = marks.findByIdSessionId(s.id).map { it.id.memberId }.toSet()
        val now = now()
        val absent = eligible(s).filter { it.id !in marked }
        marks.saveAll(absent.map { AttendanceMark(SessionMemberId(s.id, it.id), Mark.ABSENT, actorId, now, now) })
        s.status = SessionStatus.CLOSED
        s.closedAt = now
        s.closedBy = actorId
        audit.record(actorId, "training.closed", "training_session", s.id,
            "Closed ${localDate(s.startsAt)}: ${marked.size} marked, ${absent.size} absent")
        marks.flush()
        return SessionDetail(views(listOf(s), null).first(), roster(s))
    }

    // ---------- helpers ----------

    fun get(id: UUID) = sessions.findByIdAndClubId(id, clubId) ?: throw ApiException.notFound("Session")

    private fun lockAt(s: TrainingSession): Instant = s.startsAt.minus(config.availabilityCutoff)

    /** Who is expected: current members who had joined by the session day. Closed sessions: whoever was marked. */
    private fun eligible(s: TrainingSession): List<Member> {
        val day = localDate(s.startsAt)
        return members.findByClubIdOrderByMemberNo(clubId).filter { it.status.isCurrent && !it.joinedOn.isAfter(day) }
    }

    private fun roster(s: TrainingSession): List<RosterRow> {
        val avail = availability.findByIdSessionId(s.id).associateBy { it.id.memberId }
        val markMap = marks.findByIdSessionId(s.id).associateBy { it.id.memberId }
        val people = if (s.status == SessionStatus.CLOSED) {
            members.findAllById(markMap.keys + eligible(s).map { it.id }).toList()
        } else {
            eligible(s) + members.findAllById(markMap.keys - eligible(s).map { it.id }.toSet())
        }
        return people.distinctBy { it.id }.map { m ->
            val a = avail[m.id]
            RosterRow(m.id, m.fullName, m.nickname, m.jerseyNumber, a?.status ?: Availability.IN, a?.reason, markMap[m.id]?.mark)
        }.sortedWith(compareBy<RosterRow> { it.jerseyNumber ?: 999 }.thenBy { it.fullName })
    }

    private fun views(list: List<TrainingSession>, viewerId: UUID?): List<SessionView> {
        if (list.isEmpty()) return emptyList()
        val ids = list.map { it.id }
        val avail = availability.findByIdSessionIdIn(ids).groupBy { it.id.sessionId }
        val markRows = marks.findByIdSessionIdIn(ids).groupBy { it.id.sessionId }
        val now = now()
        return list.map { s ->
            val outs = avail[s.id].orEmpty().filter { it.status == Availability.OUT }
            val eligibleCount = eligible(s).size
            val mine = viewerId?.let { v -> avail[s.id].orEmpty().firstOrNull { it.id.memberId == v } }
            val local = s.startsAt.atZone(CLUB_ZONE)
            SessionView(
                id = s.id,
                startsAt = s.startsAt,
                date = local.toLocalDate(),
                time = local.toLocalTime(),
                venue = s.venue,
                kind = s.kind,
                impromptu = s.impromptu,
                focus = s.focus,
                status = s.status,
                inCount = (eligibleCount - outs.size).coerceAtLeast(0),
                outCount = outs.size,
                markedCount = markRows[s.id].orEmpty().size,
                me = viewerId?.let {
                    MyAvailability(mine?.status ?: Availability.IN, mine?.reason, !now.isBefore(lockAt(s)) || s.status != SessionStatus.SCHEDULED, lockAt(s))
                },
                myMark = viewerId?.let { v -> markRows[s.id].orEmpty().firstOrNull { it.id.memberId == v }?.mark },
            )
        }
    }

    private fun TrainingPattern.view() = PatternView(id, weekday.toInt(), startTime, venue, kind)
}
