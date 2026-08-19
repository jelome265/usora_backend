package com.usora.identity.repository;

import com.usora.identity.entity.SystemSigningKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemSigningKeyRepository extends JpaRepository<SystemSigningKey, Long> {

    Optional<SystemSigningKey> findByActiveTrue();
}
