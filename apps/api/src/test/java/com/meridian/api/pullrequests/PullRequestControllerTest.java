package com.meridian.api.pullrequests;

import com.meridian.api.auth.AuthenticatedUser;
import com.meridian.api.common.ApiException;
import com.meridian.api.common.RawJson;
import com.meridian.api.config.JacksonConfig;
import com.meridian.api.pullrequests.dto.PrDetailDto;
import com.meridian.api.pullrequests.dto.PrEventDto;
import com.meridian.api.pullrequests.dto.PrSummaryDto;
import com.meridian.api.users.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The PR risk-scoring endpoints at the HTTP boundary.
 *
 * <p>These assert the response contract rather than the query behind it: the frontend reads these
 * exact snake_case keys, and {@code score} must serialise as a JSON <em>string</em> — node-postgres
 * never parsed {@code NUMERIC} into a number, and the migration deliberately preserves that.
 */
@WebMvcTest(controllers = PullRequestController.class,
        properties = "meridian.jwt.secret=a-test-secret-long-enough-for-hs256-signing")
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonConfig.class, PullRequestControllerTest.Config.class})
class PullRequestControllerTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /**
     * With the filter chain disabled, the slice does not bring Spring Security's
     * {@code @AuthenticationPrincipal} resolver — without it the annotated parameter is treated as
     * a model attribute and silently binds an empty record. Registering the resolver explicitly
     * keeps the controller signature honest while leaving the filters off.
     */
    @TestConfiguration
    static class Config {
        @Bean
        WebMvcConfigurer authenticationPrincipalResolver() {
            return new WebMvcConfigurer() {
                @Override
                public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(new AuthenticationPrincipalArgumentResolver());
                }
            };
        }
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PullRequestService service;

    /** Puts an authenticated principal in place of the security filter chain. */
    private static RequestPostProcessor caller() {
        var principal = new AuthenticatedUser(
                UUID.randomUUID(), ORG_ID, "dev@example.test", "Dev", Role.DEVELOPER, "dev", null);
        return request -> {
            var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
            request.setUserPrincipal(auth);
            return request;
        };
    }

    private static PrSummaryDto summary() {
        return new PrSummaryDto(
                PR_ID, 412, "Refactor authentication middleware",
                "marcus-c", "https://example.test/a.png", "open",
                340, 180, 14, 6,
                List.of("refactor", "auth"), List.of("priya-r"),
                "https://github.com/acme/platform-api/pull/412",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-02T10:00:00Z"),
                null,
                "acme/platform-api",
                new BigDecimal("0.820"), "high", "medium",
                RawJson.of("[{\"rule\":\"security:sensitive-paths\",\"tier\":\"high\"}]"));
    }

    @Test
    @DisplayName("GET /prs returns the list contract the dashboard reads")
    void listReturnsExpectedShape() throws Exception {
        when(service.list(eq(ORG_ID), isNull(), isNull(), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(summary()));

        mockMvc.perform(get("/prs").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].id").value(PR_ID.toString()))
                .andExpect(jsonPath("$.items[0].number").value(412))
                .andExpect(jsonPath("$.items[0].author_login").value("marcus-c"))
                .andExpect(jsonPath("$.items[0].repo_full_name").value("acme/platform-api"))
                .andExpect(jsonPath("$.items[0].changed_files").value(14))
                .andExpect(jsonPath("$.items[0].labels[0]").value("refactor"))
                .andExpect(jsonPath("$.items[0].tier").value("high"))
                // NUMERIC stays a string, and the column's scale is preserved.
                .andExpect(jsonPath("$.items[0].score").value("0.820"))
                // Timestamps are millisecond-precision ISO-8601, as JSON.stringify(Date) produced.
                .andExpect(jsonPath("$.items[0].opened_at").value("2026-01-01T10:00:00.000Z"))
                .andExpect(jsonPath("$.items[0].merged_at").doesNotExist())
                .andExpect(jsonPath("$.items[0].rule_hits[0].rule").value("security:sensitive-paths"));
    }

    @Test
    @DisplayName("GET /prs passes its filters through")
    void listAppliesFilters() throws Exception {
        when(service.list(any(), any(), any(), any(), any(), any(Integer.class))).thenReturn(List.of());

        mockMvc.perform(get("/prs")
                        .param("state", "open")
                        .param("tier", "critical")
                        .param("repo", "acme/infra")
                        .param("author", "priya-r")
                        .param("limit", "25")
                        .with(caller()))
                .andExpect(status().isOk());

        verify(service).list(ORG_ID, "open", "acme/infra", "priya-r", "critical", 25);
    }

    @Test
    @DisplayName("GET /prs rejects values outside the allowed enumerations")
    void listRejectsInvalidFilters() throws Exception {
        mockMvc.perform(get("/prs").param("state", "exploded").with(caller()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("invalid_request")));

        mockMvc.perform(get("/prs").param("tier", "apocalyptic").with(caller()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/prs").param("limit", "5000").with(caller()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /prs/{id} returns the PR with its explanation and event history")
    void detailReturnsScoreExplanation() throws Exception {
        var detail = new PrDetailDto(
                PR_ID, UUID.randomUUID(), 412, 99L, "Refactor auth", "body",
                "marcus-c", null, "open", false, "main", "feat/x",
                340, 180, 14, 6,
                List.of("src/auth/jwt.ts"), List.of("auth"), List.of("priya-r"),
                "https://github.com/acme/platform-api/pull/412",
                Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-02T10:00:00Z"),
                null, null, null, null,
                "acme/platform-api",
                new BigDecimal("0.820"), "high", "medium",
                RawJson.of("{\"touches_auth\":1,\"additions\":340}"),
                RawJson.of("[{\"feature\":\"touches_auth\",\"weight\":0.18,\"direction\":\"up\"}]"),
                RawJson.of("[]"),
                "lgbm-synth-0.1.0", Instant.parse("2026-01-02T10:05:00Z"),
                null, null, null, null);

        when(service.detail(PR_ID, ORG_ID)).thenReturn(detail);
        when(service.events(PR_ID)).thenReturn(List.of(new PrEventDto(
                "pull_request.opened", "marcus-c",
                Instant.parse("2026-01-01T10:00:00Z"), RawJson.of("{\"action\":\"opened\"}"))));

        mockMvc.perform(get("/prs/" + PR_ID).with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pr.id").value(PR_ID.toString()))
                .andExpect(jsonPath("$.pr.score").value("0.820"))
                .andExpect(jsonPath("$.pr.tier").value("high"))
                .andExpect(jsonPath("$.pr.model_version").value("lgbm-synth-0.1.0"))
                // The jsonb columns pass through structurally, not as escaped strings.
                .andExpect(jsonPath("$.pr.features.touches_auth").value(1))
                .andExpect(jsonPath("$.pr.contributions[0].feature").value("touches_auth"))
                .andExpect(jsonPath("$.pr.file_paths[0]").value("src/auth/jwt.ts"))
                .andExpect(jsonPath("$.events[0].event_type").value("pull_request.opened"))
                .andExpect(jsonPath("$.events[0].payload.action").value("opened"));
    }

    @Test
    @DisplayName("GET /prs/{id} for another org's PR is a 404")
    void detailNotFoundIsScoped() throws Exception {
        when(service.detail(PR_ID, ORG_ID)).thenThrow(ApiException.notFound());

        mockMvc.perform(get("/prs/" + PR_ID).with(caller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    @DisplayName("POST /prs/{id}/outcome records the outcome")
    void recordsOutcome() throws Exception {
        mockMvc.perform(post("/prs/" + PR_ID + "/outcome")
                        .contentType("application/json")
                        .content("{\"reverted\":true,\"notes\":\"broke checkout\"}")
                        .with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        verify(service).recordOutcome(eq(PR_ID), eq(ORG_ID), any());
    }
}
