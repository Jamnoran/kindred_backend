package com.kindred.api.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * springdoc serves the spec at /v3/api-docs; the web repo generates its typed
 * client from it (ARCHITECTURE.md §4, "Contract flow").
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun kindredOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Kindred API")
            .description(
                "Open, transparent dating platform API. No hidden ranking, no feature paywalls, auditable code."
            )
            .version("v1")
            .license(License().name("AGPL-3.0")),
    )
}
