package com.meridian.api.common;

import org.springframework.http.HttpStatus;

/**
 * An error that is safe to show the caller verbatim.
 *
 * <p>Equivalent to the old {@code httpError(status, message)} helper, which marked errors with
 * {@code expose = true} so the error handler would echo the message instead of collapsing it to
 * {@code internal_error}. The message is always a stable machine-readable code
 * ({@code not_found}, {@code invalid_credentials}, ...) because the frontend switches on it.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException badRequest(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code);
    }

    public static ApiException unauthenticated(String code) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code);
    }

    public static ApiException forbidden(String code) {
        return new ApiException(HttpStatus.FORBIDDEN, code);
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found");
    }

    public static ApiException serviceUnavailable(String code) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code);
    }
}
