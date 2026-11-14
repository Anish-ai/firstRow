package com.firstrow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI (/swagger-ui.html) is generated from the controllers by springdoc.
 * The "bearerAuth" scheme adds an "Authorize" button where you paste a JWT from /auth/login.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI firstRowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FirstRow API")
                        .version("v1")
                        .description("Seat booking for premieres and live matches. "
                                + "Errors are RFC 7807 ProblemDetail JSON."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
