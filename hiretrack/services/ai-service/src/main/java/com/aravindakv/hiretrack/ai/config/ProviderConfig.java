package com.aravindakv.hiretrack.ai.config;

import com.aravindakv.hiretrack.ai.provider.AiProvider;
import com.aravindakv.hiretrack.ai.provider.anthropic.AnthropicProvider;
import com.aravindakv.hiretrack.ai.provider.fake.FakeAiProvider;
import com.aravindakv.hiretrack.ai.provider.openai.OpenAiProvider;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Configuration
public class ProviderConfig {

    @Bean
    AiProvider aiProvider(AiProperties props, ObjectMapper json, Environment env) {
        String provider = props.provider() == null ? "openai" : props.provider().toLowerCase();
        if (provider.equals("fake")) {
            if (env.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("The fake AI provider must never run in prod");
            }
            return new FakeAiProvider(props.fakeDelay() == null ? Duration.ofSeconds(2) : props.fakeDelay());
        }
        AiProperties.Provider cfg = switch (provider) {
            case "openai" -> props.openai();
            case "anthropic" -> props.anthropic();
            default -> throw new IllegalStateException("hiretrack.ai.provider must be openai or anthropic");
        };
        if (!StringUtils.hasText(cfg.apiKey()) || !StringUtils.hasText(cfg.matchModel())
                || !StringUtils.hasText(cfg.feedModel())) {
            throw new IllegalStateException("Set the API key, match model and feed model for provider " + provider);
        }
        return provider.equals("openai") ? new OpenAiProvider(props, json) : new AnthropicProvider(props, json);
    }
}