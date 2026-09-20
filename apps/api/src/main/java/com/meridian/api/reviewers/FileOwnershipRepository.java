package com.meridian.api.reviewers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FileOwnershipRepository extends JpaRepository<FileOwnership, FileOwnership.Key> {

    List<FileOwnership> findByRepoId(UUID repoId);
}
