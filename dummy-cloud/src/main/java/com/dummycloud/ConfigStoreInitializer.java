package com.dummycloud;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On startup, seed the H2 read model from the control workflow if it's empty — so a pre-existing
 * install shows its config in the UI immediately after a cloud restart, without waiting for the
 * first edit. Best-effort: if the workflow isn't up yet, the UI just starts empty and H2 fills in
 * on the first accepted write.
 */
@Component
public class ConfigStoreInitializer implements ApplicationRunner {

    private final ConfigStateService configState;

    public ConfigStoreInitializer(ConfigStateService configState) {
        this.configState = configState;
    }

    @Override
    public void run(ApplicationArguments args) {
        configState.hydrateIfEmpty();
    }
}
