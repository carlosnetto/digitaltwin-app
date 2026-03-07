package com.matera.digitaltwin.backoffice.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory holder for loaded mnemonics.
 * Never persisted. Populated at startup by {@link SeedLoader}.
 * Private keys are never stored — derived on demand from (mnemonic, index).
 */
@Component
public class SeedGroupRegistry {

    private final Map<UUID, String> mnemonics = new ConcurrentHashMap<>();

    public void register(UUID seedGroupId, String mnemonic) {
        mnemonics.put(seedGroupId, mnemonic);
    }

    public String getMnemonic(UUID seedGroupId) {
        String mnemonic = mnemonics.get(seedGroupId);
        if (mnemonic == null) {
            throw new IllegalStateException(
                    "Seed group " + seedGroupId + " is not loaded. Restart the server and enter the mnemonic.");
        }
        return mnemonic;
    }

    public boolean isLoaded(UUID seedGroupId) {
        return mnemonics.containsKey(seedGroupId);
    }

    public boolean hasAnyLoaded() {
        return !mnemonics.isEmpty();
    }
}
