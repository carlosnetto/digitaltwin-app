package com.matera.digitaltwin.backoffice.startup;

import com.matera.digitaltwin.backoffice.repository.SeedGroupRepository;
import com.matera.digitaltwin.backoffice.service.SeedGroupRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs at startup after Liquibase migrations complete.
 * For each active seed group, prompts the operator to type the mnemonic.
 * Mnemonics are validated and stored in-memory only — never persisted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedLoader implements CommandLineRunner {

    private final SeedGroupRepository seedGroupRepository;
    private final SeedGroupRegistry seedGroupRegistry;

    // Scanner wrapping System.in must not be closed — closing it closes stdin permanently.
    @SuppressWarnings("java:S2093")
    private final java.util.Scanner stdinScanner = new java.util.Scanner(System.in);

    @Override
    public void run(String... args) {
        List<Map<String, Object>> activeGroups = seedGroupRepository.findActive();

        if (activeGroups.isEmpty()) {
            log.warn("No active seed groups found. Wallet creation will be unavailable until one is configured.");
            return;
        }

        Console console = System.console();

        for (Map<String, Object> group : activeGroups) {
            UUID id = (UUID) group.get("id");
            String label = (String) group.get("label");

            String mnemonic = promptMnemonic(console, label);
            seedGroupRegistry.register(id, mnemonic);
            log.info("Seed group '{}' loaded successfully.", label);
        }
    }

    private String promptMnemonic(Console console, String label) {
        while (true) {
            String input;
            if (console != null) {
                char[] chars = console.readPassword("\nEnter mnemonic for seed group [%s]: ", label);
                input = new String(chars);
                Arrays.fill(chars, ' ');  // clear from memory immediately
            } else {
                // Fallback for IDEs / environments without a real console
                System.out.printf("%nEnter mnemonic for seed group [%s]: ", label);
                input = stdinScanner.nextLine();
            }

            List<String> words = Arrays.asList(input.trim().split("\\s+"));
            try {
                MnemonicCode.INSTANCE.check(words);
                return input.trim();
            } catch (MnemonicException e) {
                System.err.println("Invalid mnemonic: " + e.getMessage() + " — please try again.");
            }
        }
    }
}
