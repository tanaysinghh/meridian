package com.meridian.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression cover for the CSRF token being wiped on every authenticated request.
 *
 * <p>Spring rotates the CSRF token whenever a request authenticates. This API re-authenticates from
 * the JWT on every request, so that fired constantly and the {@code mrd_csrf} cookie alternated
 * between a value and empty — roughly half of all dashboard mutations came back 403, including
 * logout. Suppressing the delete is what makes the token stable.
 */
class StableCsrfTokenRepositoryTest {

    private static final CsrfToken TOKEN =
            new DefaultCsrfToken("X-CSRF-Token", "_csrf", "a-token-value");

    /** Records what reached the delegate. */
    private static final class RecordingRepository implements CsrfTokenRepository {
        int saveCalls;
        int nullSaves;
        CsrfToken lastSaved;

        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            return TOKEN;
        }

        @Override
        public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
            saveCalls++;
            if (token == null) {
                nullSaves++;
            }
            lastSaved = token;
        }

        @Override
        public CsrfToken loadToken(HttpServletRequest request) {
            return TOKEN;
        }
    }

    @Test
    @DisplayName("a delete (null save) never reaches the delegate")
    void suppressesTokenDeletion() {
        var delegate = new RecordingRepository();
        var repository = new StableCsrfTokenRepository(delegate);

        repository.saveToken(null, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(delegate.saveCalls).isZero();
        assertThat(delegate.nullSaves).isZero();
    }

    @Test
    @DisplayName("a real token still saves through")
    void passesThroughRealSaves() {
        var delegate = new RecordingRepository();
        var repository = new StableCsrfTokenRepository(delegate);

        repository.saveToken(TOKEN, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(delegate.saveCalls).isEqualTo(1);
        assertThat(delegate.lastSaved).isSameAs(TOKEN);
    }

    @Test
    @DisplayName("generate and load delegate unchanged")
    void delegatesGenerateAndLoad() {
        var repository = new StableCsrfTokenRepository(new RecordingRepository());

        assertThat(repository.generateToken(new MockHttpServletRequest())).isSameAs(TOKEN);
        assertThat(repository.loadToken(new MockHttpServletRequest())).isSameAs(TOKEN);
    }

    @Test
    @DisplayName("over a real cookie repository, the cookie is never blanked")
    void cookieIsNeverBlanked() {
        CookieCsrfTokenRepository cookies = CookieCsrfTokenRepository.withHttpOnlyFalse();
        cookies.setCookieName("mrd_csrf");
        cookies.setHeaderName("X-CSRF-Token");
        var repository = new StableCsrfTokenRepository(cookies);

        // Issue a token, as the first safe request does.
        var issueResponse = new MockHttpServletResponse();
        repository.saveToken(TOKEN, new MockHttpServletRequest(), issueResponse);
        assertThat(issueResponse.getCookie("mrd_csrf")).isNotNull();
        assertThat(issueResponse.getCookie("mrd_csrf").getValue()).isEqualTo("a-token-value");

        // Then the rotate-on-authentication delete, which must leave the cookie alone.
        var rotateResponse = new MockHttpServletResponse();
        repository.saveToken(null, new MockHttpServletRequest(), rotateResponse);
        assertThat(rotateResponse.getCookie("mrd_csrf"))
                .as("the delete must not emit a blanking Set-Cookie")
                .isNull();
    }
}
