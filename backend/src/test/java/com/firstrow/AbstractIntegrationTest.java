package com.firstrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrow.auth.TokenService;
import com.firstrow.user.Role;
import com.firstrow.user.User;
import com.firstrow.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

/**
 * Base class for integration tests: full Spring context + MockMvc + real Postgres (Testcontainers).
 * If Docker is not running on the machine, these tests are reported as skipped instead of failing.
 * CI (GitHub Actions ubuntu runners) always has Docker, so there they always run.
 *
 * {@code @AutoConfigureObservability}: Spring Boot turns metrics export off in tests by default,
 * which makes /actuator/prometheus return 404. We turn it back on because we test our metrics.
 * It lives on this shared base class so every integration test reuses one cached Spring context
 * (and therefore one Postgres container).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(TestcontainersConfig.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    private TokenService tokenService;

    /** Creates a user with a unique email and returns an "Authorization" header value for them. */
    protected String bearerTokenFor(Role role) {
        return bearerToken(createUser(role));
    }

    protected User createUser(Role role) {
        return users.save(new User(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                "not-a-real-hash", role, Instant.now()));
    }

    protected String bearerToken(User user) {
        return "Bearer " + tokenService.issue(user).value();
    }

    protected String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
