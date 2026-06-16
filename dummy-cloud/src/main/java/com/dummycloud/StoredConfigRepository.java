package com.dummycloud;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the UI read model. {@code findById} / {@code existsById} key on the namespace. */
public interface StoredConfigRepository extends JpaRepository<StoredConfig, String> {
}
