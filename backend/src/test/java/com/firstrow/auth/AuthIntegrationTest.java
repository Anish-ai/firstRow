package com.firstrow.auth;

import com.firstrow.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** @Transactional: each test's inserted users are rolled back afterwards. */
@Transactional
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Test
    void signupReturnsTokenAndUser() throws Exception {
        signup("New.Fan@Example.com", "correct-horse")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("new.fan@example.com"))
                .andExpect(jsonPath("$.user.role").value("USER"));
    }

    @Test
    void signupTwiceWithSameEmailIsConflict() throws Exception {
        signup("twice@example.com", "correct-horse").andExpect(status().isCreated());

        signup("TWICE@example.com", "correct-horse")
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("An account with this email already exists"));
    }

    @Test
    void signupWithInvalidInputListsEveryBadField() throws Exception {
        signup("not-an-email", "short")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void loginAfterSignupReturnsToken() throws Exception {
        signup("login@example.com", "correct-horse").andExpect(status().isCreated());

        login("login@example.com", "correct-horse")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("login@example.com"));
    }

    @Test
    void loginWithWrongPasswordIs401ProblemDetail() throws Exception {
        signup("wrongpw@example.com", "correct-horse").andExpect(status().isCreated());

        login("wrongpw@example.com", "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void malformedJsonIs400ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private ResultActions signup(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))));
    }
}
