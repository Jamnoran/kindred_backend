package com.kindred.api.chat

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.messaging.simp.SimpMessagingTemplate

const val CHAT_EVENTS_CHANNEL = "kindred:chat-events"

/** Publishes chat events to Redis so every API instance's local broker delivers them. */
class ChatEventPublisher(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun publish(event: ChatEvent) {
        redis.convertAndSend(CHAT_EVENTS_CHANNEL, objectMapper.writeValueAsString(event))
    }
}

/**
 * The "Redis relay" half of §8 realtime: Redis pub/sub fans chat events out across
 * instances; each instance re-delivers to its own in-memory STOMP broker. Disabled
 * (kindred.chat.redis-relay=false) in the openapi profile where Redis is absent —
 * broadcasts then go straight to the local broker.
 */
@Configuration
@ConditionalOnProperty("kindred.chat.redis-relay", havingValue = "true", matchIfMissing = true)
class ChatRelayConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun chatEventPublisher(redis: StringRedisTemplate, objectMapper: ObjectMapper): ChatEventPublisher =
        ChatEventPublisher(redis, objectMapper)

    @Bean
    fun chatRelayListenerContainer(
        connectionFactory: RedisConnectionFactory,
        messaging: ObjectProvider<SimpMessagingTemplate>,
        objectMapper: ObjectMapper,
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        val listener = MessageListener { message, _ ->
            try {
                val event = objectMapper.readValue(message.body, ChatEvent::class.java)
                messaging.ifAvailable?.convertAndSend("/topic/conversations/${event.conversationId}", event)
            } catch (e: Exception) {
                log.warn("dropping malformed chat event from relay: {}", e.message)
            }
        }
        container.addMessageListener(listener, ChannelTopic(CHAT_EVENTS_CHANNEL))
        return container
    }
}
