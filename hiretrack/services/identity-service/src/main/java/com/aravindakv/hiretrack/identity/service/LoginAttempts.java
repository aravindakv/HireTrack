package com.aravindakv.hiretrack.identity.service;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.identity.config.IdentityProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Spec 4: 5 failed logins per email per 15 minutes. */
@Component
public class LoginAttempts {

    private final StringRedisTemplate redis;
    private final IdentityProperties props;

    public LoginAttempts(StringRedisTemplate redis, IdentityProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void checkAllowed(String email) {
        String count = redis.opsForValue().get(key(email));
        if (count != null && Integer.parseInt(count) >= props.auth().maxFailedLogins()) {
            throw ApiException.tooManyRequests("TOO_MANY_LOGIN_ATTEMPTS",
                    "Too many failed logins. Try again in 15 minutes.",
                    (int) props.auth().failedLoginWindow().toSeconds());
        }
    }

    public void recordFailure(String email) {
        Long count = redis.opsForValue().increment(key(email));
        if (count != null && count == 1) {
            redis.expire(key(email), props.auth().failedLoginWindow());
        }
    }

    public void reset(String email) {
        redis.delete(key(email));
    }

    private static String key(String email) {
        return "login_fail:" + email;
    }
}