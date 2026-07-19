package com.kindred.api.moderation

import com.kindred.api.auth.KindredUserDetails
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Admin-only (users.is_admin, checked in AdminService per request) — 403 otherwise. */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController(private val adminService: AdminService) {

    @GetMapping("/reports")
    fun reportQueue(@AuthenticationPrincipal principal: KindredUserDetails): List<ReportedUserSummary> =
        adminService.reportQueue(principal.id)

    @PostMapping("/reports/{reportId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun dismissReport(@AuthenticationPrincipal principal: KindredUserDetails, @PathVariable reportId: Long) {
        adminService.dismissReport(principal.id, reportId)
    }

    @PostMapping("/users/{userId}/ban")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun banUser(@AuthenticationPrincipal principal: KindredUserDetails, @PathVariable userId: Long) {
        adminService.banUser(principal.id, userId)
    }

    @PostMapping("/users/{userId}/unban")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unbanUser(@AuthenticationPrincipal principal: KindredUserDetails, @PathVariable userId: Long) {
        adminService.unbanUser(principal.id, userId)
    }

    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUser(@AuthenticationPrincipal principal: KindredUserDetails, @PathVariable userId: Long) {
        adminService.deleteUser(principal.id, userId)
    }
}
