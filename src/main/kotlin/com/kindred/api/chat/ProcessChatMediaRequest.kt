package com.kindred.api.chat

import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.stereotype.Component

/** JobRequest pattern, same as ProcessProfilePhotoRequest (default value for Jackson). */
data class ProcessChatMediaRequest(val mediaId: Long = 0) : JobRequest {
    override fun getJobRequestHandler(): Class<ProcessChatMediaRequestHandler> =
        ProcessChatMediaRequestHandler::class.java
}

@Component
class ProcessChatMediaRequestHandler(
    private val chatMediaProcessingService: ChatMediaProcessingService,
) : JobRequestHandler<ProcessChatMediaRequest> {

    override fun run(jobRequest: ProcessChatMediaRequest) {
        chatMediaProcessingService.process(jobRequest.mediaId)
    }
}
