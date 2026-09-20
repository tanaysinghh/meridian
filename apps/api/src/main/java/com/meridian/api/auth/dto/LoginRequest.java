package com.meridian.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /auth/login}.
 *
 * <p>Constraints mirror the Zod schema they replace: an email of at most 320 characters and a
 * password between 1 and 200.
 *
 * <p>The email is trimmed and lowercased in the canonical constructor, which means normalisation
 * happens <em>before</em> validation — the same order the old schema's
 * {@code .trim().toLowerCase().email()} chain used. Doing it afterwards would reject
 * {@code " Dev@Example.test "}, which the previous API accepted. It also guarantees the lookup key
 * matches the lowercase form stored in {@code users.email}.
 */
public record LoginRequest(

        @NotBlank(message = "Required")
        @Email(message = "Invalid email")
        @Size(max = 320, message = "String must contain at most 320 character(s)")
        String email,

        @NotBlank(message = "Required")
        @Size(min = 1, max = 200, message = "String must contain at most 200 character(s)")
        String password
) {
    public LoginRequest {
        if (email != null) {
            email = email.trim().toLowerCase();
        }
    }
}
