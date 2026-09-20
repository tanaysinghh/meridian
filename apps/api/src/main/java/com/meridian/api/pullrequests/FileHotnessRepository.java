package com.meridian.api.pullrequests;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FileHotnessRepository extends JpaRepository<FileHotness, FileHotness.Key> {

    List<FileHotness> findByRepoId(UUID repoId);
}
