package com.meridian.api.pullrequests;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PrRiskScoreRepository extends JpaRepository<PrRiskScore, UUID> {
}
