package com.meridian.api.pullrequests;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PrOutcomeRepository extends JpaRepository<PrOutcome, UUID> {

    Optional<PrOutcome> findByPrId(UUID prId);
}
