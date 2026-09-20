package com.meridian.api.pullrequests;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PullRequestRepository extends JpaRepository<PullRequest, UUID> {

    Optional<PullRequest> findByRepoIdAndNumber(UUID repoId, int number);

    /** Org-scoped existence check, used before any write against a PR the caller named by id. */
    @Query("""
            select count(p) > 0 from PullRequest p
              join Repo r on r.id = p.repoId
             where p.id = :prId and r.orgId = :orgId
            """)
    boolean existsInOrg(@Param("prId") UUID prId, @Param("orgId") UUID orgId);

    @Query("""
            select p.id from PullRequest p
              join Repo r on r.id = p.repoId
             where r.orgId = :orgId and r.fullName = :fullName and p.number = :number
            """)
    Optional<UUID> findIdByOrgAndRepoAndNumber(@Param("orgId") UUID orgId,
                                               @Param("fullName") String fullName,
                                               @Param("number") int number);

    /**
     * Stamps the first review timestamp, once.
     *
     * <p>The {@code is null} guard is the whole point: cycle-time analytics needs the <em>first</em>
     * review, so a later review must not move it. Doing this as a conditional update rather than a
     * read-then-write keeps it correct when two review webhooks land at the same moment.
     */
    @Modifying
    @Query("update PullRequest p set p.firstReviewAt = :now where p.id = :prId and p.firstReviewAt is null")
    int stampFirstReview(@Param("prId") UUID prId, @Param("now") java.time.Instant now);

    @Modifying
    @Query("update PullRequest p set p.approvedAt = :now where p.id = :prId and p.approvedAt is null")
    int stampApproved(@Param("prId") UUID prId, @Param("now") java.time.Instant now);
}
