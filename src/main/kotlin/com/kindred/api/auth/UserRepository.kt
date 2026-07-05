package com.kindred.api.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean

    @Query("select u.id from User u where u.id in :ids and u.premiumSince is not null")
    fun findPremiumIds(@Param("ids") ids: Collection<Long>): Set<Long>
}
