package at.aimon.memory.api.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aimon.memory.jwt")
public record JwtProperties(String secret, Duration lifetime) {

    public JwtProperties {
        if (lifetime == null) {
            lifetime = Duration.ofHours(12);
        }
    }
}
