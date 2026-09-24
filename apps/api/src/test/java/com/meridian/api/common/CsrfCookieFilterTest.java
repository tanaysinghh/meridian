package com.meridian.api.common;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The CSRF token must reach the client by a route that works cross-origin.
 *
 * <p>Regression cover for the second half of the split-origin bug. The frontend read the token out
 * of {@code document.cookie}, but in production the cookie belongs to the API's domain and the SPA
 * runs on a different site — so it could never see it, and every mutating request (including
 * {@code POST /auth/login}) was rejected with 403.
 *
 * <p>Returning the same value in an {@code X-CSRF-Token} response header gives the client a source
 * that works whether or not the two share an origin. The cookie remains what the server compares
 * against; only the client's means of discovery changed.
 */
class CsrfCookieFilterTest {

    private static final CsrfToken TOKEN =
            new DefaultCsrfToken("X-CSRF-Token", "_csrf", "the-token-value");

    @Test
    @DisplayName("the token is echoed in the X-CSRF-Token response header")
    void publishesTokenAsHeader() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        request.setAttribute(CsrfToken.class.getName(), TOKEN);
        FilterChain chain = mock(FilterChain.class);

        new CsrfCookieFilter().doFilter(request, response, chain);

        assertThat(response.getHeader(CsrfCookieFilter.CSRF_HEADER)).isEqualTo("the-token-value");
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("the header name matches what the client sends back")
    void headerNameIsSymmetric() {
        // The client reads this header and echoes the value in a header of the same name, which is
        // also what CookieCsrfTokenRepository is configured to look for.
        assertThat(CsrfCookieFilter.CSRF_HEADER).isEqualTo("X-CSRF-Token");
        assertThat(TOKEN.getHeaderName()).isEqualTo(CsrfCookieFilter.CSRF_HEADER);
    }

    @Test
    @DisplayName("no token in scope means no header, and the chain still proceeds")
    void withoutTokenJustContinues() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new CsrfCookieFilter().doFilter(request, response, chain);

        assertThat(response.getHeader(CsrfCookieFilter.CSRF_HEADER)).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("a blank token is not published as an empty header")
    void blankTokenIsNotPublished() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        // DefaultCsrfToken rejects an empty value in its constructor, so the blank case can only
        // come from another CsrfToken implementation — mocked here to exercise the guard.
        CsrfToken blank = mock(CsrfToken.class);
        org.mockito.Mockito.when(blank.getToken()).thenReturn("");
        request.setAttribute(CsrfToken.class.getName(), blank);
        FilterChain chain = mock(FilterChain.class);

        new CsrfCookieFilter().doFilter(request, response, chain);

        // An empty header would make the client think it had a token and send nothing useful.
        assertThat(response.getHeader(CsrfCookieFilter.CSRF_HEADER)).isNull();
        verify(chain).doFilter(request, response);
    }
}
