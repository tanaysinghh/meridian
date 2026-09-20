package com.meridian.api.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /** Org roster for the settings page, ordered the way the old query was: by role, then name. */
    List<User> findByOrgIdOrderByRoleAscNameAsc(UUID orgId);

    /** Candidate reviewers — only accounts we can map to a GitHub identity. */
    List<User> findByOrgIdAndGithubLoginIsNotNull(UUID orgId);

    /**
     * Scoped role update. The org id in the WHERE clause is what stops an admin of one org from
     * re-roling a user in another; it was in the old SQL for the same reason.
     */
    @Modifying
    @Query("update User u set u.role = :role where u.id = :id and u.orgId = :orgId")
    int updateRole(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("role") Role role);
}
