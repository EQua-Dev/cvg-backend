package ng.cvgfc.api.member

import ng.cvgfc.api.config.CvgProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Creates the first admin from configuration when the club has no members yet,
 * so a fresh deployment can be signed into without touching the database.
 */
@Component
class BootstrapAdmin(
    private val members: MemberRepository,
    private val service: MemberService,
    private val properties: CvgProperties,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val phone = properties.bootstrap.adminPhone?.takeIf { it.isNotBlank() } ?: return
        val name = properties.bootstrap.adminName?.takeIf { it.isNotBlank() } ?: "Club Admin"
        if (members.countByClubId(properties.clubId) > 0) return

        val admin = service.create(
            CreateMemberRequest(fullName = name, phone = phone, status = MemberStatus.ACTIVE, roles = setOf(Role.ADMIN)),
            actorId = null,
        )
        log.info("Bootstrapped first admin {} ({})", admin.fullName, admin.code)
    }
}
