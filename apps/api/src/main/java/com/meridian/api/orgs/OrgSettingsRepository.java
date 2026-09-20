package com.meridian.api.orgs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrgSettingsRepository extends JpaRepository<OrgSettings, UUID> {
}
