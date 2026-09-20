package com.meridian.api.common;

import com.meridian.api.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Single place where exceptions become response bodies.
 *
 * <p>Successor to {@code src/middleware/error.js}. The contract it upholds:
 * <ul>
 *   <li>Exposed errors → {@code {"error": "<code>"}} at their own status.</li>
 *   <li>Validation failures → {@code 400 {"error": "invalid_request: <field>: <message>; ..."}},
 *       the same concatenated shape the Zod handler produced.</li>
 *   <li>Everything else → {@code 500 {"error": "internal_error"}}, with {@code message} and
 *       {@code stack} added only when the dev profile asks for them.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AppProperties props;

    public GlobalExceptionHandler(AppProperties props) {
        this.props = props;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("error", ex.getMessage()));
    }

    /** {@code @Valid} failures on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBodyValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getAllErrors().stream()
                .map(err -> {
                    String field = err instanceof FieldError fe ? fe.getField() : "(root)";
                    return field + ": " + err.getDefaultMessage();
                })
                .collect(Collectors.joining("; "));
        return invalidRequest(detail);
    }

    /** {@code @Validated} failures on query params and path variables. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleParamValidation(HandlerMethodValidationException ex) {
        String detail = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(err -> result.getMethodParameter().getParameterName() + ": " + err.getDefaultMessage()))
                .collect(Collectors.joining("; "));
        return invalidRequest(detail.isBlank() ? "(root): invalid parameters" : detail);
    }

    /**
     * Constraint violations on query parameters and path variables.
     *
     * <p>A controller annotated {@code @Validated} is proxied for method validation, and that path
     * raises {@link ConstraintViolationException} rather than the {@code HandlerMethodValidation}
     * exception above. Without this handler a bad {@code ?limit=5000} would surface as a 500
     * instead of the 400 the previous API returned.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> parameterName(violation) + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return invalidRequest(detail.isBlank() ? "(root): invalid parameters" : detail);
    }

    /** Trims the {@code method.parameter} property path down to just the parameter name. */
    private static String parameterName(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot >= 0 && lastDot < path.length() - 1 ? path.substring(lastDot + 1) : path;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return invalidRequest(ex.getParameterName() + ": Required");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return invalidRequest(ex.getName() + ": Invalid value");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return invalidRequest("(root): malformed request body");
    }

    /**
     * Unmatched routes. The old service ended its middleware chain with an explicit
     * {@code next(httpError(404, 'not_found'))}, so the body has to be {@code {"error":"not_found"}}
     * rather than Spring's default problem detail.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled_error method={} path={} type={} message={}",
                request.getMethod(), request.getRequestURI(), ex.getClass().getName(), ex.getMessage(), ex);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "internal_error");
        if (props.exposeErrorDetails()) {
            body.put("message", ex.getMessage());
            body.put("stack", stackTrace(ex));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<Map<String, Object>> invalidRequest(String detail) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request: " + detail));
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
