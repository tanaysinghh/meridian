package com.meridian.api.rules;

import com.meridian.api.auth.AuthenticatedUser;
import com.meridian.api.common.ApiException;
import com.meridian.api.common.Tuples;
import com.meridian.api.repos.RepoRepository;
import com.meridian.api.rules.dto.RuleRequests;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /rules} — per-repo escalation rules.
 *
 * <p>Reading is open to the whole org; creating, editing and deleting are limited to admins and
 * team leads. Writes are additionally scoped: the target repo (on create) or the rule itself (on
 * edit and delete) must belong to the caller's org, so a valid id from another tenant is a 404
 * rather than a successful write.
 */
@RestController
@RequestMapping("/rules")
public class RulesController {

    private static final String LIST_SQL = """
            SELECT rr.id, rr.name, rr.enabled, rr.predicate, rr.action, rr.created_at,
                   r.full_name AS repo_full_name, r.id AS repo_id
              FROM repo_rules rr JOIN repos r ON r.id = rr.repo_id
             WHERE r.org_id = :orgId
             ORDER BY r.full_name, rr.name
            """;

    @PersistenceContext
    private EntityManager em;

    private final RepoRuleRepository rules;
    private final RepoRepository repos;
    private final ObjectMapper objectMapper;

    public RulesController(RepoRuleRepository rules, RepoRepository repos, ObjectMapper objectMapper) {
        this.rules = rules;
        this.repos = repos;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> list(@AuthenticationPrincipal AuthenticatedUser user) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery(LIST_SQL, Tuple.class)
                .setParameter("orgId", user.orgId())
                .getResultList();

        List<RuleRequests.Summary> items = rows.stream()
                .map(t -> new RuleRequests.Summary(
                        Tuples.uuid(t, "id"),
                        Tuples.string(t, "name"),
                        Boolean.TRUE.equals(Tuples.bool(t, "enabled")),
                        Tuples.json(t, "predicate"),
                        Tuples.json(t, "action"),
                        Tuples.instant(t, "created_at"),
                        Tuples.string(t, "repo_full_name"),
                        Tuples.uuid(t, "repo_id")))
                .toList();

        return Map.of("items", items);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_LEAD')")
    @Transactional
    public Map<String, Object> create(@AuthenticationPrincipal AuthenticatedUser user,
                                      @Valid @RequestBody RuleRequests.Create body) {

        if (!repos.existsByIdAndOrgId(body.repoId(), user.orgId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "repo_not_found");
        }

        RepoRule rule = new RepoRule(
                body.repoId(),
                body.name(),
                body.enabledOrDefault(),
                writeJson(body.predicate()),
                writeJson(body.action()));

        RepoRule saved = rules.save(rule);
        return Map.of("id", saved.getId());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_LEAD')")
    @Transactional
    public Map<String, Object> update(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable UUID id,
                                      @Valid @RequestBody RuleRequests.Patch body) {

        // A rule outside the caller's org simply isn't found, so this is a no-op — the same
        // outcome the previous scoped UPDATE produced.
        rules.findByIdInOrg(id, user.orgId()).ifPresent(rule -> {
            if (body.name() != null) {
                rule.setName(body.name());
            }
            if (body.predicate() != null) {
                rule.setPredicate(writeJson(body.predicate()));
            }
            if (body.action() != null) {
                rule.setAction(writeJson(body.action()));
            }
            if (body.enabled() != null) {
                rule.setEnabled(body.enabled());
            }
            rules.save(rule);
        });

        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_LEAD')")
    @Transactional
    public Map<String, Object> delete(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable UUID id) {
        rules.deleteByIdInOrg(id, user.orgId());
        return Map.of("ok", true);
    }

    /** Serialises a validated predicate or action back to the JSON stored in the jsonb column. */
    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
