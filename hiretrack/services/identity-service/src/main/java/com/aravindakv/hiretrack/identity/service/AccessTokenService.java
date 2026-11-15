package com.aravindakv.hiretrack.identity.service;

import com.aravindakv.hiretrack.identity.config.IdentityProperties;
import com.aravindakv.hiretrack.identity.domain.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class AccessTokenService {

    private final RSAKey key;
    private final IdentityProperties props;
    private final Clock clock;

    public AccessTokenService(RSAKey key, IdentityProperties props, Clock clock) {
        this.key = key;
        this.props = props;
        this.clock = clock;
    }

    public String issue(User user) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuer(props.jwt().issuer())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(props.jwt().accessTokenTtl())))
                .jwtID(UUID.randomUUID().toString())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign access token", e);
        }
    }

    public long ttlSeconds() {
        return props.jwt().accessTokenTtl().toSeconds();
    }

    /** Public half only: safe to publish. */
    public Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }
}