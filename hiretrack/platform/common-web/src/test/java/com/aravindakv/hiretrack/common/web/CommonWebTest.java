package com.aravindakv.hiretrack.common.web;

import com.aravindakv.hiretrack.testsupport.TestJwt;
import com.aravindakv.hiretrack.testsupport.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = WebTestApp.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class CommonWebTest {

    @Autowired MockMvc mvc;

    @Test
    void missingTokenGives401InStandardFormat() throws Exception {
        mvc.perform(get("/me"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void currentUserComesFromTokenSubject() throws Exception {
        mvc.perform(get("/me").header("Authorization", "Bearer " + TestJwt.token(42)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.userId").value(42));
    }

    @Test
    void apiExceptionMapsToStatusAndCode() throws Exception {
        mvc.perform(get("/boom").header("Authorization", "Bearer " + TestJwt.token(1)))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error.code").value("THING_TAKEN"))
           .andExpect(jsonPath("$.error.message").value("That thing is taken."));
    }

    @Test
    void validationErrorsAreReadable() throws Exception {
        mvc.perform(post("/things").header("Authorization", "Bearer " + TestJwt.token(1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
           .andExpect(jsonPath("$.error.message").value("name must not be blank"));
    }

    @Test
    void internalEndpointNeedsInternalToken() throws Exception {
        mvc.perform(get("/internal/ping"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.error.code").value("INTERNAL_AUTH_REQUIRED"));
        mvc.perform(get("/internal/ping").header("X-Internal-Token", "test-internal-token"))
           .andExpect(status().isOk());
    }

    @Test
    void requestIdIsEchoed() throws Exception {
        mvc.perform(get("/me").header("Authorization", "Bearer " + TestJwt.token(1))
                        .header("X-Request-Id", "req-12345678"))
           .andExpect(header().string("X-Request-Id", "req-12345678"));
    }
}