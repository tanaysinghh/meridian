package com.meridian.api.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByRefreshToken(String refreshToken);

    /**
     * Returns the number of rows removed, which is how the refresh path proves it — and not a
     * concurrent request that arrived with the same token — is the one that consumed the session.
     * Only the winner of that race is allowed to mint the replacement.
     */
    @Modifying
    @Query("delete from Session s where s.refreshToken = :token")
    int deleteByRefreshToken(@Param("token") String token);
}
