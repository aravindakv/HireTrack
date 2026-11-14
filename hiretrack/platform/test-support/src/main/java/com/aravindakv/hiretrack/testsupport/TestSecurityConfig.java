package com.aravindakv.hiretrack.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Replaces the key-file/JWKS decoder with one that trusts {@link TestJwt}.
 * @Primary wins over any JwtDecoder a service defines itself (identity-service does).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(TestJwt.publicKey()).build();
    }
}