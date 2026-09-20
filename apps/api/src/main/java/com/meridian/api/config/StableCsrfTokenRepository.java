package com.meridian.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * A CSRF token repository that refuses to delete the token.
 *
 * <p>Spring attaches {@code CsrfAuthenticationStrategy} to the filter chain, which rotates the CSRF
 * token whenever a request authenticates — it deletes the current one by calling
 * {@code saveToken(null, …)} and leaves a replacement to be generated later. That is the right
 * behaviour for form login, where authentication happens once per session and rotating the token
 * closes a fixation window.
 *
 * <p>This API is stateless: it re-authenticates from the JWT on <em>every</em> request, so the
 * strategy fired on every request too. The observable result was an {@code mrd_csrf} cookie that
 * alternated between empty and a fresh value on successive calls, and any mutation that happened to
 * land while it was empty was rejected with a 403. Logout failed roughly half the time; so would
 * any other write the dashboard made.
 *
 * <p>Suppressing the delete is the narrow fix. The token's lifetime is then tied to its cookie, and
 * it is cleared deliberately at exactly one point — {@code AuthCookies.clearAll()} on logout, which
 * expires {@code mrd_csrf} alongside the session cookies.
 *
 * <p>Nothing about the CSRF defence itself is weakened: the token is still random per browser,
 * still unreadable cross-origin behind {@code SameSite=Lax}, and still required to match on every
 * mutating request. Only the rotation-on-authentication is dropped, and with a stateless JWT there
 * is no session to fixate.
 */
public class StableCsrfTokenRepository implements CsrfTokenRepository {

    private final CsrfTokenRepository delegate;

    public StableCsrfTokenRepository(CsrfTokenRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (token == null) {
            // The "delete the token" call. Ignored — see the class comment.
            return;
        }
        delegate.saveToken(token, request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate.loadToken(request);
    }
}
