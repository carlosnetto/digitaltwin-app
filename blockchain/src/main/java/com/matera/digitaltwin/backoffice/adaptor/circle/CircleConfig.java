package com.matera.digitaltwin.backoffice.adaptor.circle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

// TODO: Circle integration is not yet active.
//       Set circle.enabled=true and supply circle.api-key to enable.
//       See TODO.md for the full Circle implementation checklist.
@Configuration
@ConditionalOnProperty(prefix = "circle", name = "enabled", havingValue = "true")
public class CircleConfig {

    @Value("${circle.base-url}")
    private String baseUrl;

    @Value("${circle.api-key}")
    private String apiKey;

    @Bean
    public WebClient circleWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
