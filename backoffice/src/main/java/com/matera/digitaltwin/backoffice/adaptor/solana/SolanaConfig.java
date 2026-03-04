package com.matera.digitaltwin.backoffice.adaptor.solana;

import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.solana.programs.clients.NativeProgramClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;

/**
 * Spring configuration for the official Solana Java SDK (Sava).
 * https://sava.software
 *
 * <p>Beans:
 * <ul>
 *   <li>{@link HttpClient} — shared Java HTTP client (thread-safe singleton)
 *   <li>{@link SolanaRpcClient} — Sava's async JSON-RPC client
 *   <li>{@link SolanaAccounts} — canonical program addresses (system, token, ATA, etc.)
 *   <li>{@link NativeProgramClient} — instruction builder for native/SPL programs
 * </ul>
 */
@Configuration
public class SolanaConfig {

    @Value("${solana.rpc-url}")
    private String rpcUrl;

    @Bean
    public HttpClient solanaHttpClient() {
        return HttpClient.newHttpClient();
    }

    @Bean
    public SolanaRpcClient solanaRpcClient(HttpClient solanaHttpClient) {
        return SolanaRpcClient.build()
                .endpoint(URI.create(rpcUrl))
                .httpClient(solanaHttpClient)
                .defaultCommitment(Commitment.CONFIRMED)
                .createClient();
    }

    /**
     * Standard Solana program addresses.
     * MAIN_NET constants are identical to devnet — system/token programs use the
     * same address on every cluster. Only token mints differ (set in application.yml).
     */
    @Bean
    public SolanaAccounts solanaAccounts() {
        return SolanaAccounts.MAIN_NET;
    }

    @Bean
    public NativeProgramClient nativeProgramClient(SolanaAccounts solanaAccounts) {
        return NativeProgramClient.createClient(solanaAccounts);
    }
}
