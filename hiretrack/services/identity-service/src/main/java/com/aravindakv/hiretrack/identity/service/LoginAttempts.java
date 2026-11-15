package com.aravindakv.hiretrack.identity.service;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.identity.config.IdentityProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** Spec 4: 5 failed logins per email per 15 minutes. */
@Component
public class LoginAttempts {

    /** INCR and set the TTL on the first increment, in one atomic step, so a crash can't leave a key without expiry. */
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return n""", Long.class);

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
        redis.execute(INCR_WITH_TTL, List.of(key(email)),
                String.valueOf(props.auth().failedLoginWindow().toSeconds()));   // window starts at the first failure
    }

    public void reset(String email) {
        redis.delete(key(email));
    }

    private static String key(String email) {
        return "login_fail:" + email;
    }
}