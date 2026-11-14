package com.firstrow.auth;

import com.firstrow.config.FirstRowProperties;
import com.firstrow.user.Role;
import com.firstrow.user.User;
import com.firstrow.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;

/**
 * Creates the first ADMIN account at startup from ADMIN_EMAIL / ADMIN_PASSWORD, if both are set
 * and no user with that email exists yet. We don't seed an admin in a Flyway migration because
 * that would ship a known password to every environment, production included.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final FirstRowProperties.Admin adminProps;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AdminBootstrap(FirstRowProperties props, UserRepository users, PasswordEncoder passwordEncoder,
                          Clock clock) {
        this.adminProps = props.admin();
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(adminProps.email()) || !StringUtils.hasText(adminProps.password())) {
            return;
        }
        String email = AuthService.normalizeEmail(adminProps.email());
        if (users.existsByEmail(email)) {
            return;
        }
        users.save(new User(email, passwordEncoder.encode(adminProps.password()), Role.ADMIN, clock.instant()));
        log.info("admin.bootstrap created admin account {}", email);
    }
}
