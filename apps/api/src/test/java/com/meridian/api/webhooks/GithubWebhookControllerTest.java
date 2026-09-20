package com.meridian.api.webhooks;

import com.meridian.api.realtime.RealtimeGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook ingestion at the HTTP boundary.
 *
 * <p>The signature verifier is the real one — the point of these tests is that an unverified
 * delivery never reaches the ingestion service at all, which a mocked verifier could not show.
 */
@WebMvcTest(controllers = GithubWebhookController.class,
        properties = {
                "meridian.github.webhook-secret=" + WebhookTestConfig.SECRET,
                "meridian.github.allow-unsigned-webhooks=false",
                "meridian.jwt.secret=a-test-secret-long-enough-for-hs256-signing"
        })
@AutoConfigureMockMvc(addFilters = false)
@Import(WebhookTestConfig.class)
class GithubWebhookControllerTest {

    private static final String PAYLOAD = """
            {"action":"opened",
             "repository":{"id":1,"full_name":"acme/platform-api","default_branch":"main"},
             "pull_request":{"id":2,"number":7,"title":"t","user":{"login":"marcus-c"},
                             "state":"open","merged":false,"draft":false,
                             "base":{"ref":"main"},"head":{"ref":"f"},
                             "additions":1,"deletions":0,"changed_files":1,"commits":1,
                             "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"},
             "sender":{"login":"marcus-c"}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebhookIngestService ingest;

    @MockitoBean
    private RealtimeGateway realtime;

    @Test
    @DisplayName("rejects a delivery with no signature and does not ingest it")
    void rejectsUnsignedDelivery() throws Exception {
        mockMvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("bad_signature"));

        verify(ingest, never()).ingestPullRequest(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("rejects a delivery signed with the wrong secret")
    void rejectsWrongSignature() throws Exception {
        mockMvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", WebhookTestConfig.sign("the-wrong-secret", PAYLOAD))
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("bad_signature"));

        verify(ingest, never()).ingestPullRequest(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("accepts a correctly signed pull_request and ingests it")
    void acceptsSignedPullRequest() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID prId = UUID.randomUUID();
        when(ingest.resolveOrgId("acme/platform-api")).thenReturn(Optional.of(orgId));
        when(ingest.shouldScore("opened")).thenReturn(true);
        when(ingest.ingestPullRequest(any(), anyString(), anyString(), any()))
                .thenReturn(new WebhookIngestService.IngestResult(
                        prId, UUID.randomUUID(), "acme/platform-api", 7, "t", "marcus-c",
                        null, 1, 0, 1, 1, java.util.List.of(), java.util.List.of(),
                        java.time.Instant.now(), java.util.List.of()));

        mockMvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", WebhookTestConfig.sign(WebhookTestConfig.SECRET, PAYLOAD))
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        verify(ingest).ingestPullRequest(any(), anyString(), anyString(), any());
        // The dashboard is told, and scoring is kicked off off-thread.
        verify(realtime).prUpdated(orgId, prId, "opened");
        verify(ingest).scoreAndPersist(any(), any(), anyString());
    }

    @Test
    @DisplayName("accepts the delivery but skips ingestion when no org owns the repo")
    void acceptsButIgnoresUnknownRepo() throws Exception {
        when(ingest.resolveOrgId(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", WebhookTestConfig.sign(WebhookTestConfig.SECRET, PAYLOAD))
                        .contentType("application/json")
                        .content(PAYLOAD))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ignored").value("no_org"));

        verify(ingest, never()).ingestPullRequest(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("acknowledges an event type it does not handle rather than failing it")
    void acknowledgesUnhandledEvent() throws Exception {
        String payload = "{\"action\":\"created\"}";

        mockMvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "issue_comment")
                        .header("X-Hub-Signature-256", WebhookTestConfig.sign(WebhookTestConfig.SECRET, payload))
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.ignored").value(true));
    }

    @Test
    @DisplayName("a signed but unparseable body is a 400, not a 500")
    void rejectsMalformedJson() throws Exception {
        String payload = "{not json";

        mockMvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", WebhookTestConfig.sign(WebhookTestConfig.SECRET, payload))
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_payload"));
    }
}
