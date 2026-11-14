package com.firstrow.auth;

import com.firstrow.user.Role;
import com.firstrow.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** BCrypt only uses the first 72 bytes of a password, hence the max size (also re-checked in bytes). */
    public record SignupRequest(@NotBlank @Email @Size(max = 255) String email,
                                @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank @Email String email,
                               @NotBlank String password) {
    }

    public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, UserView user) {

        public static AuthResponse of(TokenService.IssuedToken token, User user) {
            return new AuthResponse(token.value(), "Bearer", token.expiresAt(), UserView.from(user));
        }
    }

    public record UserView(Long id, String email, Role role) {

        public static UserView from(User user) {
            return new UserView(user.getId(), user.getEmail(), user.getRole());
        }
    }
}
