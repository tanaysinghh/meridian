package com.meridian.api.reviewers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Inferred code ownership: who has been committing under a given path prefix.
 *
 * <p>A lightweight stand-in for CODEOWNERS, derived from commit history rather than declared. It is
 * the primary signal behind reviewer suggestions — someone who has touched a directory forty times
 * is a better reviewer for it than someone who never has.
 *
 * <p>Natural composite key {@code (repo_id, path_prefix, owner_login)}; there is no surrogate id
 * because the triple is the identity.
 */
@Entity
@Table(name = "file_ownership")
@IdClass(FileOwnership.Key.class)
public class FileOwnership {

    @Id
    @Column(name = "repo_id", nullable = false)
    private UUID repoId;

    @Id
    @Column(name = "path_prefix", nullable = false)
    private String pathPrefix;

    @Id
    @Column(name = "owner_login", nullable = false)
    private String ownerLogin;

    @Column(name = "commits", nullable = false)
    private int commits;

    @Column(name = "last_touched", nullable = false, insertable = false, updatable = false)
    private Instant lastTouched;

    protected FileOwnership() {
    }

    public UUID getRepoId() {
        return repoId;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public String getOwnerLogin() {
        return ownerLogin;
    }

    public int getCommits() {
        return commits;
    }

    public Instant getLastTouched() {
        return lastTouched;
    }

    /** Composite primary key. */
    public static class Key implements Serializable {
        private UUID repoId;
        private String pathPrefix;
        private String ownerLogin;

        public Key() {
        }

        public Key(UUID repoId, String pathPrefix, String ownerLogin) {
            this.repoId = repoId;
            this.pathPrefix = pathPrefix;
            this.ownerLogin = ownerLogin;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(repoId, key.repoId)
                    && Objects.equals(pathPrefix, key.pathPrefix)
                    && Objects.equals(ownerLogin, key.ownerLogin);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repoId, pathPrefix, ownerLogin);
        }
    }
}
