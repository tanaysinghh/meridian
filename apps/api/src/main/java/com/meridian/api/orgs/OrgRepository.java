package com.meridian.api.orgs;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrgRepository extends JpaRepository<Org, UUID> {

    Optional<Org> findBySlug(String slug);

    /**
     * The oldest org, used wherever the code has to pick "the" org in a single-tenant deployment —
     * the GitHub OAuth upsert and the webhook's repo-to-org fallback both do this. Mirrors
     * {@code SELECT id FROM orgs ORDER BY created_at LIMIT 1}.
     */
    Optional<Org> findFirstByOrderByCreatedAtAsc();

    java.util.List<Org> findAllByOrderByCreatedAtAsc(Limit limit);
}
