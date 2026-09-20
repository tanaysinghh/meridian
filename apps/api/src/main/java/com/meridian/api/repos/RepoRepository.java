package com.meridian.api.repos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface RepoRepository extends JpaRepository<Repo, UUID> {

    Optional<Repo> findByOrgIdAndFullName(UUID orgId, String fullName);

    Optional<Repo> findFirstByFullName(String fullName);

    boolean existsByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Scoped threshold update. The org predicate is the authorization check — a repo id belonging to
     * another org matches nothing and the update is a no-op, which is how the old SQL behaved.
     */
    @Modifying
    @Query("update Repo r set r.riskThreshold = :threshold where r.id = :id and r.orgId = :orgId")
    int updateRiskThreshold(@Param("id") UUID id,
                            @Param("orgId") UUID orgId,
                            @Param("threshold") BigDecimal threshold);
}
