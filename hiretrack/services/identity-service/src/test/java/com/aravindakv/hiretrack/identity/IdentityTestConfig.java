package com.aravindakv.hiretrack.identity;

import com.aravindakv.hiretrack.testsupport.TestJwt;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** identity-service signs with the same key the tests' JwtDecoder trusts. */
@TestConfiguration(proxyBeanMethods = false)
public class IdentityTestConfig {
    @Bean
    RSAKey signingKey() {
        return TestJwt.rsaKey();
    }
}