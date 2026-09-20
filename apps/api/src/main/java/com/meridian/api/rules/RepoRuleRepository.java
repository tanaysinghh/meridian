package com.meridian.api.rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoRuleRepository extends JpaRepository<RepoRule, UUID> {

    List<RepoRule> findByRepoId(UUID repoId);

    /**
     * A rule by id, but only if it belongs to the given org. Every mutation goes through this so
     * the org check cannot be skipped by accident.
     */
    @Query("""
            select rr from RepoRule rr
             where rr.id = :id
               and rr.repoId in (select r.id from Repo r where r.orgId = :orgId)
            """)
    Optional<RepoRule> findByIdInOrg(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Modifying
    @Query("""
            delete from RepoRule rr
             where rr.id = :id
               and rr.repoId in (select r.id from Repo r where r.orgId = :orgId)
            """)
    int deleteByIdInOrg(@Param("id") UUID id, @Param("orgId") UUID orgId);
}
