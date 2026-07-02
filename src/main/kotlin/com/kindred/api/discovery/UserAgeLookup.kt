package com.kindred.api.discovery

import com.kindred.api.auth.UserRepository
import com.kindred.api.profile.ProfileNotFoundException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.Period

@Component
class UserAgeLookup(
    private val users: UserRepository,
    private val clock: Clock,
) {
    fun ageOf(userId: Long): Int {
        val user = users.findById(userId).orElseThrow { ProfileNotFoundException() }
        return Period.between(user.dob, LocalDate.now(clock)).years
    }
}
