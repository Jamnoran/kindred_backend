package com.kindred.api.chat

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the relay against a real Redis (localhost:6379): two relay "instances"
 * each with their own listener container and local broker, one publishes, both must
 * deliver. Skipped (JUnit assumption) when no Redis is reachable, so plain CI runs
 * stay green.
 */
class ChatEventRelayRedisTest {

    private val closeables = mutableListOf<() -> Unit>()

    @AfterEach
    fun tearDown() = closeables.reversed().forEach { it() }

    private class Instance(factory: LettuceConnectionFactory, objectMapper: ObjectMapper) {
        val delivered = CopyOnWriteArrayList<ChatEvent>()
        val latch = CountDownLatch(1)
        private val broker: SimpMessagingTemplate = mock {
            on { convertAndSend(any<String>(), any<Any>()) } doAnswer {
                delivered += it.arguments[1] as ChatEvent
                latch.countDown()
            }
        }
        private val messaging: ObjectProvider<SimpMessagingTemplate> = mock {
            on { ifAvailable } doReturn broker
        }
        val relay = ChatEventRelay(StringRedisTemplate(factory), messaging, objectMapper)
        val container = org.springframework.data.redis.listener.RedisMessageListenerContainer().apply {
            setConnectionFactory(factory)
            addMessageListener(
                org.springframework.data.redis.connection.MessageListener { message, _ ->
                    relay.deliverLocally(String(message.body, Charsets.UTF_8))
                },
                org.springframework.data.redis.listener.ChannelTopic(ChatEventRelay.CHANNEL),
            )
            afterPropertiesSet()
            start()
        }
    }

    @Test
    fun `an event published on one instance reaches subscribers on every instance`() {
        val factory = LettuceConnectionFactory("localhost", 6379).apply { afterPropertiesSet() }
        closeables += { factory.destroy() }
        assumeTrue(
            runCatching { factory.connection.use { it.ping() } }.isSuccess,
            "no Redis on localhost:6379 — skipping relay integration test",
        )

        val objectMapper = ObjectMapper().findAndRegisterModules()
        val a = Instance(factory, objectMapper)
        val b = Instance(factory, objectMapper)
        closeables += { a.container.stop(); b.container.stop() }

        val event = ChatEvent(type = "typing", conversationId = 42L, typingUserId = 7L)
        a.relay.publish(event)

        assertTrue(a.latch.await(5, TimeUnit.SECONDS), "publishing instance never delivered locally")
        assertTrue(b.latch.await(5, TimeUnit.SECONDS), "second instance never received the relayed event")
        assertEquals(listOf(event), a.delivered)
        assertEquals(listOf(event), b.delivered)
    }
}
