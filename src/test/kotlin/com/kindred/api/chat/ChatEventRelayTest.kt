package com.kindred.api.chat

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.time.Instant
import kotlin.test.assertEquals

class ChatEventRelayTest {

    private val redis: StringRedisTemplate = mock()
    private val messagingTemplate: SimpMessagingTemplate = mock()
    private val messaging: ObjectProvider<SimpMessagingTemplate> = mock {
        on { ifAvailable } doReturn messagingTemplate
    }
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val relay = ChatEventRelay(redis, messaging, objectMapper)

    @Test
    fun `published events survive the redis round-trip and reach the local broker`() {
        val event = ChatEvent(
            type = "message",
            conversationId = 7L,
            message = MessageResponse(
                id = 100L,
                senderId = 1L,
                body = "hello",
                createdAt = Instant.parse("2026-07-02T12:00:00Z"),
                readAt = null,
            ),
        )

        relay.publish(event)

        val payload = argumentCaptor<String>()
        verify(redis).convertAndSend(eq(ChatEventRelay.CHANNEL), payload.capture())
        relay.deliverLocally(payload.firstValue)

        val delivered = argumentCaptor<ChatEvent>()
        verify(messagingTemplate).convertAndSend(eq("/topic/conversations/7"), delivered.capture())
        assertEquals(event, delivered.firstValue)
    }

    @Test
    fun `typing events carry the typing user`() {
        val event = ChatEvent(type = "typing", conversationId = 3L, typingUserId = 2L)

        relay.deliverLocally(objectMapper.writeValueAsString(event))

        val delivered = argumentCaptor<ChatEvent>()
        verify(messagingTemplate).convertAndSend(eq("/topic/conversations/3"), delivered.capture())
        assertEquals(event, delivered.firstValue)
    }

    @Test
    fun `malformed relay payloads are dropped, not thrown`() {
        relay.deliverLocally("not json at all")
        relay.deliverLocally("""{"conversationId":1}""") // missing required type

        verifyNoInteractions(messagingTemplate)
    }
}
