package com.kindred.api.chat

import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.stereotype.Controller
import java.security.Principal

/** Client sends to /app/conversations/{id}/typing; participants see it on the topic. */
@Controller
class TypingController(private val chatService: ChatService) {

    @MessageMapping("/conversations/{id}/typing")
    fun typing(@DestinationVariable id: Long, principal: Principal) {
        val userId = SubscriptionAuthInterceptor.userIdOf(principal)
        chatService.requireMembership(userId, id)
        chatService.broadcast(ChatEvent(type = "typing", conversationId = id, typingUserId = userId))
    }
}
