package com.eventrelay.core.repository;

import com.eventrelay.core.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlugAndDeletedAtIsNull(String slug);

    Optional<Tenant> findByIdAndDeletedAtIsNull(UUID id);
}
