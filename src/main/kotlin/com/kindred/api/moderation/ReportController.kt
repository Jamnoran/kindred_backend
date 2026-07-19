package com.kindred.api.moderation

import com.kindred.api.auth.KindredUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class ReportController(private val reportService: ReportService) {

    /** Report a user for bot behavior, catfishing, inappropriate behavior, etc. */
    @PostMapping("/{userId}/report")
    @ResponseStatus(HttpStatus.CREATED)
    fun report(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @PathVariable userId: Long,
        @Valid @RequestBody req: ReportRequest,
    ): ReportResponse = ReportResponse.from(reportService.fileReport(principal.id, userId, req.reason, req.details))
}
