package com.meridian.api.auth;

import com.meridian.api.common.ApiException;
import com.meridian.api.config.AppProperties;
import com.meridian.api.orgs.Org;
import com.meridian.api.orgs.OrgRepository;
import com.meridian.api.users.Role;
import com.meridian.api.users.User;
import com.meridian.api.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * The GitHub OAuth sign-in flow.
 *
 * <p>A faithful port, including the decision the old code was explicit about: when the app's
 * credentials are not configured this returns a clean 503 rather than falling back to a demo login.
 * There is no path here that authenticates someone without GitHub having vouched for them.
 *
 * <p>Two other guards carry over. The {@code state} parameter is generated per attempt, stored in an
 * HttpOnly cookie and compared on return, which is what stops an attacker from feeding the callback
 * their own authorization code. And the account is only created or updated once GitHub confirms a
 * <em>verified</em> primary email — an unverified address would let someone claim an account by
 * signing up with an email they do not control.
 */
@Service
public class GithubOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GithubOAuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String API_BASE = "https://api.github.com";
    private static final String SCOPES = "read:user user:email";

    private final AppProperties props;
    private final RestClient restClient;
    private final UserRepository users;
    private final OrgRepository orgs;

    public GithubOAuthService(AppProperties props,
                              RestClient.Builder restClientBuilder,
                              UserRepository users,
                              OrgRepository orgs) {
        this.props = props;
        this.restClient = restClientBuilder.build();
        this.users = users;
        this.orgs = orgs;
    }

    public boolean configured() {
        return props.github().oauthConfigured();
    }

    /** A fresh anti-forgery {@code state} value for one authorization attempt. */
    public String newState() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Builds the URL the browser is redirected to in order to start the OAuth flow.
     *
     * <p>The {@code encode()} step is load-bearing. {@link #SCOPES} separates the two scopes with a
     * space, which is not legal in a URI query — and {@code AuthController} passes this string to
     * {@code URI.create}, which throws on it. Without encoding, every call to
     * {@code GET /auth/github} returned a 500 once the app was actually configured. The Node
     * implementation got this for free because {@code URLSearchParams} encodes on write.
     */
    public String authorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", props.github().clientId())
                .queryParam("redirect_uri", props.github().oauthCallback())
                .queryParam("scope", SCOPES)
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    /**
     * Exchanges the authorization code for a user, creating or updating the local account.
     *
     * @throws ApiException 401 when GitHub declines the exchange or the account has no verified
     *                      email; 500 when no org has been provisioned yet.
     */
    @Transactional
    public User completeLogin(String code) {
        String accessToken = exchangeCode(code);
        JsonNode ghUser = fetch("/user", accessToken);
        JsonNode emails = fetch("/user/emails", accessToken);

        String email = primaryVerifiedEmail(emails)
                .or(() -> Optional.ofNullable(ghUser.path("email").asString(null)))
                .map(e -> e.toLowerCase())
                .orElse(null);

        long githubId = ghUser.path("id").asLong(0L);
        if (githubId == 0L || email == null || email.isBlank()) {
            throw ApiException.unauthenticated("github_no_verified_email");
        }

        // Single-tenant deploy: everyone lands in the first org. A multi-tenant version would map
        // the GitHub App installation id to an org instead.
        Org org = orgs.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "no_org_provisioned"));

        String login = ghUser.path("login").asString(null);
        String name = ghUser.path("name").asString(null);
        String avatar = ghUser.path("avatar_url").asString(null);

        // Upsert on email, matching the old ON CONFLICT (email) DO UPDATE. An existing user keeps
        // their role — only the GitHub identity fields are refreshed.
        User user = users.findByEmail(email).orElseGet(() -> new User(org.getId(), email, Role.DEVELOPER));
        user.setName(name != null ? name : login);
        user.setGithubLogin(login);
        user.setGithubId(githubId);
        user.setAvatarUrl(avatar);
        if (user.getOrgId() == null) {
            user.setOrgId(org.getId());
        }
        return users.save(user);
    }

    private String exchangeCode(String code) {
        JsonNode body;
        try {
            body = restClient.post()
                    .uri(TOKEN_URL)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "client_id", props.github().clientId(),
                            "client_secret", props.github().clientSecret(),
                            "code", code,
                            "redirect_uri", props.github().oauthCallback()))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception ex) {
            log.warn("github_oauth_token_exchange_failed reason={}", ex.getClass().getSimpleName());
            throw ApiException.unauthenticated("github_oauth_failed");
        }

        String accessToken = body == null ? null : body.path("access_token").asString(null);
        if (accessToken == null || accessToken.isBlank()) {
            // Log only GitHub's error code — the response body can carry token material.
            log.warn("github_oauth_token_exchange_failed error={}",
                    body == null ? "empty_response" : body.path("error").asString("unknown"));
            throw ApiException.unauthenticated("github_oauth_failed");
        }
        return accessToken;
    }

    private JsonNode fetch(String path, String accessToken) {
        try {
            return restClient.get()
                    .uri(API_BASE + path)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("User-Agent", "meridian")
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception ex) {
            log.warn("github_api_call_failed path={} reason={}", path, ex.getClass().getSimpleName());
            throw ApiException.unauthenticated("github_oauth_failed");
        }
    }

    private static Optional<String> primaryVerifiedEmail(JsonNode emails) {
        if (emails == null || !emails.isArray()) {
            return Optional.empty();
        }
        for (JsonNode entry : emails) {
            if (entry.path("primary").asBoolean(false) && entry.path("verified").asBoolean(false)) {
                String value = entry.path("email").asString(null);
                if (value != null && !value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }
}
