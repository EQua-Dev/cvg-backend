package ng.cvgfc.api.money

import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.member.Role
import ng.cvgfc.api.profile.photoResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.YearMonth
import java.util.UUID

data class CategoryOption(val code: Category, val label: String, val collectable: Boolean, val expense: Boolean)

@RestController
@PreAuthorize("hasAnyRole('ADMIN','TREASURER')")
class MoneyController(private val collections: CollectionService, private val ledger: LedgerService) {

    @GetMapping("/api/money/categories")
    fun categories() = Category.entries.map { CategoryOption(it, it.label, it.collectable, it.expense) }

    // --- collections ---

    @GetMapping("/api/collections")
    fun list(@RequestParam(defaultValue = "false") open: Boolean) = collections.list(open)

    @PostMapping("/api/collections")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody req: CreateCollectionRequest, @AuthenticationPrincipal me: CurrentMember) =
        collections.create(req, me.id)

    @GetMapping("/api/collections/{id}")
    fun detail(@PathVariable id: UUID) = collections.detail(id)

    @PutMapping("/api/collections/{id}/members")
    fun setMembers(@PathVariable id: UUID, @RequestBody req: SetMembersRequest, @AuthenticationPrincipal me: CurrentMember) =
        collections.setMembers(id, req.memberIds, me.id)

    @PostMapping("/api/collections/{id}/close")
    fun close(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = collections.close(id, me.id)

    // --- ledger ---

    @GetMapping("/api/ledger")
    fun month(@RequestParam(required = false) month: YearMonth?) = ledger.month(month)

    @PostMapping("/api/ledger/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun payment(@RequestBody req: PaymentRequest, @AuthenticationPrincipal me: CurrentMember) = ledger.recordPayment(req, me.id)

    @PostMapping("/api/ledger/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    fun expense(@RequestBody req: ExpenseRequest, @AuthenticationPrincipal me: CurrentMember) = ledger.recordExpense(req, me.id)

    @PostMapping("/api/ledger/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    fun reverse(@PathVariable id: UUID, @RequestBody req: ReverseRequest, @AuthenticationPrincipal me: CurrentMember) =
        ledger.reverse(id, req, me.id)

    @PutMapping("/api/ledger/{id}/receipt", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun receipt(@PathVariable id: UUID, @RequestParam("file") file: MultipartFile, @AuthenticationPrincipal me: CurrentMember) =
        ledger.attachReceipt(id, file.bytes, me.id)
}

/** What any member can see about their own money. */
@RestController
class MyMoneyController(private val collections: CollectionService, private val ledger: LedgerService) {

    @GetMapping("/api/me/dues")
    fun dues(@AuthenticationPrincipal me: CurrentMember) = collections.myDues(me.id)

    /** Treasurer/admin, or the member the entry is about. */
    @GetMapping("/api/ledger/{id}/receipt")
    fun receipt(
        @PathVariable id: UUID,
        @AuthenticationPrincipal me: CurrentMember,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> {
        val allowed = me.has(Role.ADMIN) || me.has(Role.TREASURER) || ledger.entryMemberId(id) == me.id
        if (!allowed) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return photoResponse(ledger.receipt(id), ifNoneMatch, public = false)
    }
}

/** Starts each month's dues automatically, just after midnight in Abuja. */
@Component
class RecurringCollectionsJob(private val collections: CollectionService) {
    @Scheduled(cron = "0 10 0 * * *", zone = "Africa/Lagos")
    fun run() {
        collections.rollRecurring()
    }
}
