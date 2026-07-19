package com.kindred.api.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * springdoc serves the spec at /v3/api-docs; the web repo generates its typed
 * client from it (ARCHITECTURE.md §4, "Contract flow"). The Gradle task
 * `generateOpenApiDocs` exports it to openapi/kindred-api.json.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun kindredOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Kindred API")
                .description(
                    "Open, transparent dating platform API. No hidden ranking, no feature paywalls, auditable code."
                )
                .version("v1")
                .license(License().name("AGPL-3.0")),
        )
        // Logout is handled by Spring Security's logout filter, so springdoc can't
        // discover it from a controller — declare it by hand to keep the contract complete.
        .paths(
            Paths().addPathItem(
                "/api/v1/auth/logout",
                PathItem().post(
                    Operation()
                        .addTagsItem("auth-controller")
                        .operationId("logout")
                        .summary("Invalidate the current session")
                        .responses(
                            ApiResponses().addApiResponse("204", ApiResponse().description("Logged out")),
                        ),
                ),
            ),
        )
}
