package com.kindred.api.meta

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class MetaResponse(
    val name: String,
    val version: String,
    val openApiSpec: String,
    val source: String,
)

/**
 * Public instance metadata. Lets clients (and other self-hosted instances)
 * discover what they are talking to — part of the transparency promise.
 */
@RestController
@RequestMapping("/api/v1/meta")
class MetaController(
    @param:Value("\${kindred.version:dev}") private val version: String,
) {

    @GetMapping
    fun meta(): MetaResponse = MetaResponse(
        name = "kindred-api",
        version = version,
        openApiSpec = "/v3/api-docs",
        source = "https://github.com/jamnoran/kindred_backend",
    )
}
