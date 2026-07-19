package com.kindred.api.chat

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Multi-instance fan-out for chat events over Redis pub/sub (§8).
 *
 * ChatService publishes every event here instead of straight to the local STOMP
 * broker. The event goes out on one Redis channel; every API instance — the
 * publisher included — receives it and rebroadcasts to its own in-memory broker,
 * so a WebSocket client can be connected to any instance and still see events
 * produced on another. The openapi spec-export boot runs without Redis, hence the
 * profile guard; broadcasts are a no-op there (it serves no clients).
 */
@Component
@Profile("!openapi")
class ChatEventRelay(
    private val redis: StringRedisTemplate,
    // lazy: SimpMessagingTemplate comes from the WebSocket config, which depends on ChatService
    private val messaging: ObjectProvider<SimpMessagingTemplate>,
    private val objectMapper: ObjectMapper,
) {

    fun publish(event: ChatEvent) {
        redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event))
    }

    /** Runs on every instance for every published event (the publisher included). */
    fun deliverLocally(json: String) {
        val event = try {
            objectMapper.readValue(json, ChatEvent::class.java)
        } catch (e: Exception) {
            log.warn("dropping malformed chat event from the relay channel", e)
            return
        }
        messaging.ifAvailable?.convertAndSend("/topic/conversations/${event.conversationId}", event)
    }

    companion object {
        /**
         * One channel for all conversations — per-conversation filtering already
         * happens at the STOMP subscription, and a channel per conversation would
         * need dynamic Redis (un)subscribes for no gain at this scale.
         */
        const val CHANNEL = "kindred:chat:events"
        private val log = LoggerFactory.getLogger(ChatEventRelay::class.java)
    }
}

@Configuration
@Profile("!openapi")
class ChatEventRelayConfig {

    @Bean
    fun chatEventListenerContainer(
        connectionFactory: RedisConnectionFactory,
        relay: ChatEventRelay,
    ): RedisMessageListenerContainer = RedisMessageListenerContainer().apply {
        setConnectionFactory(connectionFactory)
        addMessageListener(
            MessageListener { message, _ -> relay.deliverLocally(String(message.body, Charsets.UTF_8)) },
            ChannelTopic(ChatEventRelay.CHANNEL),
        )
    }
}
