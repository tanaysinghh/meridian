package com.meridian.api.pullrequests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A pull request as Meridian tracks it.
 *
 * <p>The state column collapses GitHub's {@code state} plus {@code merged} flag into the three
 * values the dashboard filters on: {@code open}, {@code closed}, {@code merged}. That mapping
 * happens on ingestion, where {@code merged} wins over {@code closed}.
 *
 * <p>{@code firstReviewAt} and {@code approvedAt} are stamped once and never overwritten — they feed
 * the cycle-time breakdown, which needs the first occurrence, not the latest.
 */
@Entity
@Table(name = "pull_requests")
public class PullRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repo_id", nullable = false)
    private UUID repoId;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "github_id", unique = true)
    private Long githubId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body")
    private String body;

    @Column(name = "author_login", nullable = false)
    private String authorLogin;

    @Column(name = "author_avatar")
    private String authorAvatar;

    /** One of {@code open}, {@code closed}, {@code merged}. */
    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "draft", nullable = false)
    private boolean draft;

    @Column(name = "base_ref", nullable = false)
    private String baseRef;

    @Column(name = "head_ref", nullable = false)
    private String headRef;

    @Column(name = "additions", nullable = false)
    private int additions;

    @Column(name = "deletions", nullable = false)
    private int deletions;

    @Column(name = "changed_files", nullable = false)
    private int changedFiles;

    @Column(name = "commits_count", nullable = false)
    private int commitsCount;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "file_paths", nullable = false)
    private List<String> filePaths = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "labels", nullable = false)
    private List<String> labels = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "requested_reviewers", nullable = false)
    private List<String> requestedReviewers = new ArrayList<>();

    @Column(name = "url")
    private String url;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "merged_at")
    private Instant mergedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "first_review_at")
    private Instant firstReviewAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    protected PullRequest() {
    }

    public PullRequest(UUID repoId, int number) {
        this.repoId = repoId;
        this.number = number;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRepoId() {
        return repoId;
    }

    public int getNumber() {
        return number;
    }

    public Long getGithubId() {
        return githubId;
    }

    public void setGithubId(Long githubId) {
        this.githubId = githubId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getAuthorLogin() {
        return authorLogin;
    }

    public void setAuthorLogin(String authorLogin) {
        this.authorLogin = authorLogin;
    }

    public String getAuthorAvatar() {
        return authorAvatar;
    }

    public void setAuthorAvatar(String authorAvatar) {
        this.authorAvatar = authorAvatar;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public boolean isDraft() {
        return draft;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    public String getBaseRef() {
        return baseRef;
    }

    public void setBaseRef(String baseRef) {
        this.baseRef = baseRef;
    }

    public String getHeadRef() {
        return headRef;
    }

    public void setHeadRef(String headRef) {
        this.headRef = headRef;
    }

    public int getAdditions() {
        return additions;
    }

    public void setAdditions(int additions) {
        this.additions = additions;
    }

    public int getDeletions() {
        return deletions;
    }

    public void setDeletions(int deletions) {
        this.deletions = deletions;
    }

    public int getChangedFiles() {
        return changedFiles;
    }

    public void setChangedFiles(int changedFiles) {
        this.changedFiles = changedFiles;
    }

    public int getCommitsCount() {
        return commitsCount;
    }

    public void setCommitsCount(int commitsCount) {
        this.commitsCount = commitsCount;
    }

    public List<String> getFilePaths() {
        return filePaths;
    }

    public void setFilePaths(List<String> filePaths) {
        this.filePaths = filePaths == null ? new ArrayList<>() : filePaths;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels == null ? new ArrayList<>() : labels;
    }

    public List<String> getRequestedReviewers() {
        return requestedReviewers;
    }

    public void setRequestedReviewers(List<String> requestedReviewers) {
        this.requestedReviewers = requestedReviewers == null ? new ArrayList<>() : requestedReviewers;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getMergedAt() {
        return mergedAt;
    }

    public void setMergedAt(Instant mergedAt) {
        this.mergedAt = mergedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public Instant getFirstReviewAt() {
        return firstReviewAt;
    }

    public void setFirstReviewAt(Instant firstReviewAt) {
        this.firstReviewAt = firstReviewAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }
}
