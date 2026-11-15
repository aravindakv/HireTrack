package com.aravindakv.hiretrack.identity.service;

import com.aravindakv.hiretrack.common.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @Test
    void acceptsEightToSeventyTwoBytes() {
        assertThatCode(() -> PasswordPolicy.check("12345678")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.check("a".repeat(72))).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortAndOverlongPasswords() {
        assertThatThrownBy(() -> PasswordPolicy.check("1234567")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("a".repeat(73))).isInstanceOf(ApiException.class);
        // 25 three-byte characters = 75 bytes: too long even though it's only 25 characters
        assertThatThrownBy(() -> PasswordPolicy.check("€".repeat(25))).isInstanceOf(ApiException.class);
    }
}