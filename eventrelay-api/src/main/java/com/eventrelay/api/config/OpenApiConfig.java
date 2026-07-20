package com.eventrelay.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "ApiKey";

    @Bean
    public OpenAPI eventRelayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EventRelay API")
                        .version("v1")
                        .description("""
                                Reliable webhook delivery platform.

                                Events are accepted here and delivered asynchronously with
                                at-least-once semantics: retries with exponential backoff and
                                jitter, a dead-letter queue with replay, and HMAC-SHA256 request
                                signing (including Standard Webhooks headers).

                                Authenticate with the tenant API key as a bearer token.
                                """)
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("Tenant API key returned once at tenant creation.")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
