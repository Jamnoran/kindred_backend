package com.kindred.api.chat

import com.kindred.api.auth.KindredUserDetails
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.security.core.Authentication
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import java.security.Principal
import org.springframework.messaging.Message as MessagingMessage

/** Event envelope broadcast on /topic/conversations/{id}. */
data class ChatEvent(
    val type: String, // message | read | typing | media | presence
    val conversationId: Long,
    val message: MessageResponse? = null,
    val readerId: Long? = null,
    val typingUserId: Long? = null,
    /** media events: the processed (or rejected) image attached to an earlier message */
    val media: ChatMediaSummary? = null,
    /** presence events */
    val presenceUserId: Long? = null,
    val online: Boolean? = null,
)

/**
 * STOMP over WebSocket (§8). The handshake at /ws is authenticated by the session
 * cookie; subscriptions to conversation topics are authorized by match membership.
 * The broker here is per-instance and in-memory; ChatEventRelay fans events out
 * across instances over Redis pub/sub before they reach it.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val chatService: ChatService,
    @Value("\${kindred.websocket.allowed-origins:}") private val allowedOrigins: String,
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = allowedOrigins.split(",").map(String::trim).filter(String::isNotEmpty)
        val ep = registry.addEndpoint("/ws")
        if (origins.isNotEmpty()) ep.setAllowedOrigins(*origins.toTypedArray())
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic")
        registry.setApplicationDestinationPrefixes("/app")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(SubscriptionAuthInterceptor(chatService))
    }
}

class SubscriptionAuthInterceptor(private val chatService: ChatService) : ChannelInterceptor {

    companion object {
        private val CONVERSATION_TOPIC = Regex("/topic/conversations/(\\d+)(/.*)?")

        fun userIdOf(principal: Principal?): Long {
            val details = (principal as? Authentication)?.principal as? KindredUserDetails
                ?: throw org.springframework.messaging.MessagingException("unauthenticated")
            return details.id
        }
    }

    override fun preSend(message: MessagingMessage<*>, channel: MessageChannel): MessagingMessage<*> {
        val accessor = StompHeaderAccessor.wrap(message)
        if (accessor.command == StompCommand.SUBSCRIBE) {
            val userId = userIdOf(accessor.user)
            val match = CONVERSATION_TOPIC.matchEntire(accessor.destination ?: "")
                ?: throw org.springframework.messaging.MessagingException("unknown destination")
            // throws ConversationNotFoundException for non-members — subscription denied
            chatService.requireMembership(userId, match.groupValues[1].toLong())
        }
        return message
    }
}
