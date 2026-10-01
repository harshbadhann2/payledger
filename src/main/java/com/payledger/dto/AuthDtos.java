package com.payledger.dto;

import com.payledger.domain.Role;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull
            @Size(min = 8, max = 72, message = "password must be 8-72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "password must contain a letter and a digit")
            String password,
            @NotNull Role role) {

        @AssertTrue(message = "role must be CUSTOMER or MERCHANT")
        public boolean isRegistrableRole() {
            return role != Role.SYSTEM;
        }

        /** BCrypt only reads the first 72 bytes; longer input would silently be truncated. */
        @AssertTrue(message = "password must be at most 72 bytes")
        public boolean isPasswordWithinBcryptLimit() {
            return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
        }
    }

    public record RegisterResponse(Long userId, Long walletId, String email, Role role) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
    }
}
