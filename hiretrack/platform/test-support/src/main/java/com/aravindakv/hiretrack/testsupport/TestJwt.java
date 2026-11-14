package com.aravindakv.hiretrack.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

/** One RSA key pair per test JVM, used to sign and verify test tokens. */
public final class TestJwt {

    private static final RSAKey KEY = generate();

    private TestJwt() {}

    public static RSAKey rsaKey() { return KEY; }

    public static RSAPublicKey publicKey() {
        try {
            return KEY.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String token(long userId) {
        return token(userId, "user" + userId + "@example.com", 3600);
    }

    public static String token(long userId, String email, long ttlSeconds) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(Long.toString(userId))
                .claim("email", email)
                .issuer("hiretrack-identity")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}