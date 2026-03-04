package com.matera.digitaltwin.backoffice.controller;

import com.matera.digitaltwin.backoffice.adaptor.solana.SolanaAdaptor;
import com.matera.digitaltwin.backoffice.dto.request.DeriveAddressRequest;
import com.matera.digitaltwin.backoffice.dto.request.SendSolRequest;
import com.matera.digitaltwin.backoffice.dto.request.SendSplTokenRequest;
import com.matera.digitaltwin.backoffice.dto.response.BalanceResponse;
import com.matera.digitaltwin.backoffice.dto.response.SendResultResponse;
import com.matera.digitaltwin.backoffice.dto.response.TransactionResponse;
import com.matera.digitaltwin.backoffice.dto.response.WalletInfoResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/solana")
@RequiredArgsConstructor
@Validated
public class SolanaController {

    private final SolanaAdaptor solanaAdaptor;

    /** POST /api/solana/wallet — generate a new Solana keypair. */
    @PostMapping("/wallet")
    public ResponseEntity<WalletInfoResponse> generateWallet() {
        return ResponseEntity.ok(solanaAdaptor.generateWallet());
    }

    /**
     * POST /api/solana/wallet/derive — derive a Solana address from a BIP39 mnemonic.
     * Accepts 12 or 24 words. Uses path m/44'/501'/{accountIndex}'/0'.
     * The mnemonic is kept in the request body (never in URL / server logs).
     */
    @PostMapping("/wallet/derive")
    public ResponseEntity<Map<String, Object>> deriveAddress(
            @Valid @RequestBody DeriveAddressRequest request) {
        var wallet = solanaAdaptor.deriveAddress(request.getMnemonic(), request.getAccountIndex());
        return ResponseEntity.ok(Map.of(
                "address", wallet.getAddress(),
                "privateKey", wallet.getPrivateKey(),
                "accountIndex", request.getAccountIndex(),
                "network", wallet.getNetwork()
        ));
    }

    /** GET /api/solana/wallet/{address}/balance — native SOL balance. */
    @GetMapping("/wallet/{address}/balance")
    public ResponseEntity<BalanceResponse> getNativeBalance(@PathVariable String address) {
        return ResponseEntity.ok(solanaAdaptor.getNativeBalance(address));
    }

    /** GET /api/solana/wallet/{address}/token/{mint}/balance — SPL token balance. */
    @GetMapping("/wallet/{address}/token/{mint}/balance")
    public ResponseEntity<BalanceResponse> getTokenBalance(
            @PathVariable String address,
            @PathVariable String mint) {
        return ResponseEntity.ok(solanaAdaptor.getTokenBalance(address, mint));
    }

    /** POST /api/solana/send/sol — send native SOL. */
    @PostMapping("/send/sol")
    public ResponseEntity<SendResultResponse> sendSol(@Valid @RequestBody SendSolRequest request) {
        return ResponseEntity.ok(
                solanaAdaptor.sendNative(request.getFromPrivateKey(),
                        request.getToAddress(), request.getAmount()));
    }

    /** POST /api/solana/send/token — send SPL token (USDC / USDT). */
    @PostMapping("/send/token")
    public ResponseEntity<SendResultResponse> sendToken(
            @Valid @RequestBody SendSplTokenRequest request) {
        return ResponseEntity.ok(
                solanaAdaptor.sendToken(request.getFromPrivateKey(),
                        request.getToAddress(), request.getTokenMint(), request.getAmount()));
    }

    /** GET /api/solana/wallet/{address}/history?limit=20 — all transaction history. */
    @GetMapping("/wallet/{address}/history")
    public ResponseEntity<List<TransactionResponse>> getHistory(
            @PathVariable String address,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(solanaAdaptor.getTransactionHistory(address, limit));
    }

    /** GET /api/solana/wallet/{address}/token/{mint}/history?limit=20 — token-specific history via ATA. */
    @GetMapping("/wallet/{address}/token/{mint}/history")
    public ResponseEntity<List<TransactionResponse>> getTokenHistory(
            @PathVariable String address,
            @PathVariable String mint,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(solanaAdaptor.getTokenTransactionHistory(address, mint, limit));
    }

    /** GET /api/solana/tx/{txHash}/status — check if a transaction is confirmed. */
    @GetMapping("/tx/{txHash}/status")
    public ResponseEntity<Map<String, Object>> getTxStatus(@PathVariable String txHash) {
        boolean confirmed = solanaAdaptor.isConfirmed(txHash);
        return ResponseEntity.ok(Map.of(
                "txHash", txHash,
                "confirmed", confirmed,
                "explorerUrl", solanaAdaptor.getExplorerUrl(txHash)
        ));
    }
}
