package com.eventrelay.core.repository;

import com.eventrelay.core.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyPrefixAndRevokedAtIsNull(String keyPrefix);
}
