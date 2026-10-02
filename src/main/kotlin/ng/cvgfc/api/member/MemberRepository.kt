package ng.cvgfc.api.member

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface MemberRepository : JpaRepository<Member, UUID> {

    fun findByIdAndClubId(id: UUID, clubId: UUID): Member?

    fun findByClubIdAndPhone(clubId: UUID, phone: String): Member?

    fun findByClubIdOrderByMemberNo(clubId: UUID): List<Member>

    fun countByClubId(clubId: UUID): Long

    fun existsByClubIdAndPhoneAndIdNot(clubId: UUID, phone: String, id: UUID): Boolean

    fun existsByClubIdAndJerseyNumberAndStatusInAndIdNot(
        clubId: UUID, jerseyNumber: Short, statuses: Collection<MemberStatus>, id: UUID,
    ): Boolean

    @Query("select coalesce(max(m.memberNo), 0) from Member m where m.clubId = :clubId")
    fun maxMemberNo(clubId: UUID): Int
}
