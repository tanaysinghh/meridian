package com.meridian.api.pullrequests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * How often a path churns, and how often that churn went wrong.
 *
 * <p>Recomputed periodically rather than maintained transactionally — it is a heuristic input to
 * scoring ({@code hot_file_overlap}), not a source of truth. A PR touching paths with a high hotness
 * score is, empirically, likelier to be reverted.
 *
 * <p>Natural composite key {@code (repo_id, path)}.
 */
@Entity
@Table(name = "file_hotness")
@IdClass(FileHotness.Key.class)
public class FileHotness {

    @Id
    @Column(name = "repo_id", nullable = false)
    private UUID repoId;

    @Id
    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "change_count", nullable = false)
    private int changeCount;

    @Column(name = "revert_count", nullable = false)
    private int revertCount;

    @Column(name = "incident_count", nullable = false)
    private int incidentCount;

    @Column(name = "score", nullable = false, precision = 4, scale = 3)
    private BigDecimal score = BigDecimal.ZERO;

    @Column(name = "last_seen", nullable = false, insertable = false, updatable = false)
    private Instant lastSeen;

    protected FileHotness() {
    }

    public UUID getRepoId() {
        return repoId;
    }

    public String getPath() {
        return path;
    }

    public int getChangeCount() {
        return changeCount;
    }

    public int getRevertCount() {
        return revertCount;
    }

    public int getIncidentCount() {
        return incidentCount;
    }

    public BigDecimal getScore() {
        return score;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    /** Composite primary key. */
    public static class Key implements Serializable {
        private UUID repoId;
        private String path;

        public Key() {
        }

        public Key(UUID repoId, String path) {
            this.repoId = repoId;
            this.path = path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(repoId, key.repoId) && Objects.equals(path, key.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repoId, path);
        }
    }
}
