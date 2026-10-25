package com.example.messaging.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** springdoc metadata. The committed spec lives in {@code openapi/messaging-api-v1.yaml}. */
@Configuration
public class OpenApiConfig {

    public static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI messagingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Omnichannel Messaging and Notification Orchestration API")
                        .version("1.0.0")
                        .description("Synthetic-data reference platform. Providers are simulators; no real message is "
                                + "ever sent."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
