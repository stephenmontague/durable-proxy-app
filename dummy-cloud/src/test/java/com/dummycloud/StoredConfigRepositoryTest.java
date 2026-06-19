package com.dummycloud;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the H2 read-model layer: the {@link StoredConfig} entity maps cleanly (including the
 * large {@code @Lob} stateJson) and round-trips through {@link StoredConfigRepository}. A JPA slice
 * test — no Temporal, no web — so it just confirms the persistence wiring an operator's config relies on.
 */
@DataJpaTest
class StoredConfigRepositoryTest {

    @Autowired
    private StoredConfigRepository repository;

    @Test
    void savesAndReadsBackTheConfigByNamespace() {
        String stateJson = "{\"enabled\":true,\"version\":3,\"devices\":[],\"catalogEntries\":["
                + "{\"type\":\"DIVERT_COMMAND\",\"direction\":\"CLOUD_TO_EDGE\",\"codec\":\"raw\"}]}";
        repository.save(new StoredConfig("sandbox-a", stateJson, 3, "2026-06-16T00:00:00Z"));

        StoredConfig found = repository.findById("sandbox-a").orElseThrow();
        assertThat(found.getVersion()).isEqualTo(3);
        assertThat(found.getStateJson()).isEqualTo(stateJson);
        assertThat(found.getUpdatedAt()).isEqualTo("2026-06-16T00:00:00Z");
    }

    @Test
    void upsertReplacesTheRowForANamespace() {
        repository.save(new StoredConfig("sandbox-b", "{\"version\":1}", 1, "t1"));
        repository.save(new StoredConfig("sandbox-b", "{\"version\":2}", 2, "t2"));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById("sandbox-b").orElseThrow().getVersion()).isEqualTo(2);
    }
}
