package com.kindred.api.notification

import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.stereotype.Component

/** JobRequest pattern, same as ProcessChatMediaRequest (default values for Jackson). */
data class SendNotificationRequest(
    val type: NotificationType = NotificationType.new_match,
    val recipientUserId: Long = 0,
    val otherUserId: Long = 0,
    val conversationId: Long = 0,
) : JobRequest {
    override fun getJobRequestHandler(): Class<SendNotificationRequestHandler> =
        SendNotificationRequestHandler::class.java
}

@Component
class SendNotificationRequestHandler(
    private val notificationService: NotificationService,
) : JobRequestHandler<SendNotificationRequest> {

    override fun run(jobRequest: SendNotificationRequest) {
        notificationService.dispatch(jobRequest)
    }
}
