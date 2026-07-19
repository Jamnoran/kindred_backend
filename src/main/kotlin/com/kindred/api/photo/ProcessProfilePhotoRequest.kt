package com.kindred.api.photo

import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.stereotype.Component

/**
 * JobRequest pattern (instead of lambda analysis — more robust from Kotlin). The
 * default value gives Jackson the no-arg constructor it needs for deserialization.
 */
data class ProcessProfilePhotoRequest(val photoId: Long = 0) : JobRequest {
    override fun getJobRequestHandler(): Class<ProcessProfilePhotoRequestHandler> =
        ProcessProfilePhotoRequestHandler::class.java
}

@Component
class ProcessProfilePhotoRequestHandler(
    private val photoProcessingService: PhotoProcessingService,
) : JobRequestHandler<ProcessProfilePhotoRequest> {

    override fun run(jobRequest: ProcessProfilePhotoRequest) {
        photoProcessingService.process(jobRequest.photoId)
    }
}
