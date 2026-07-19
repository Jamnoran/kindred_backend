package com.kindred.api.chat

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises presence against a real Redis (localhost:6379) with a controllable
 * clock, so the crashed-instance aging path is tested for real. Skipped (JUnit
 * assumption) when no Redis is reachable, so plain CI runs stay green.
 */
class PresenceServiceRedisTest {

    private lateinit var factory: LettuceConnectionFactory
    private lateinit var redis: StringRedisTemplate
    private var now: Instant = Instant.parse("2026-07-03T12:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
    }
    private val userId = 987_654_321L // unlikely to collide with anything else in a shared dev Redis

    @BeforeEach
    fun setUp() {
        factory = LettuceConnectionFactory("localhost", 6379).apply { afterPropertiesSet() }
        assumeTrue(
            runCatching { factory.connection.use { it.ping() } }.isSuccess,
            "no Redis on localhost:6379 — skipping presence integration test",
        )
        redis = StringRedisTemplate(factory)
        redis.delete("${PresenceService.KEY_PREFIX}$userId")
    }

    @AfterEach
    fun tearDown() {
        if (::redis.isInitialized) redis.delete("${PresenceService.KEY_PREFIX}$userId")
        factory.destroy()
    }

    @Test
    fun `connect, disconnect, and multi-session transitions`() {
        val presence = PresenceService(redis, clock)

        assertFalse(presence.isOnline(userId))

        presence.markOnline(userId, "session-a")
        presence.markOnline(userId, "session-b")
        assertTrue(presence.isOnline(userId))

        presence.markOffline(userId, "session-a")
        assertTrue(presence.isOnline(userId), "still online through the second session")

        presence.markOffline(userId, "session-b")
        assertFalse(presence.isOnline(userId))
    }

    @Test
    fun `sessions that stop being refreshed age out of the online window`() {
        val presence = PresenceService(redis, clock)
        presence.markOnline(userId, "session-from-crashed-instance")

        now = now.plus(PresenceService.ONLINE_WINDOW).plus(Duration.ofSeconds(1))

        assertFalse(presence.isOnline(userId), "unrefreshed session must read offline")
    }

    @Test
    fun `onlineOf filters a batch`() {
        val presence = PresenceService(redis, clock)
        presence.markOnline(userId, "s1")

        assertEquals(setOf(userId), presence.onlineOf(listOf(userId, userId + 1)))
    }
}
