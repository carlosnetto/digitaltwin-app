package com.matera.digitaltwin.backoffice.controller;

import com.matera.digitaltwin.backoffice.dto.request.CreateWalletRequest;
import com.matera.digitaltwin.backoffice.dto.request.SendTokenRequest;
import com.matera.digitaltwin.backoffice.dto.response.CreateWalletResponse;
import com.matera.digitaltwin.backoffice.dto.response.SendTokenResponse;
import com.matera.digitaltwin.backoffice.service.TransactionProvisioningService;
import com.matera.digitaltwin.backoffice.service.WalletProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletProvisioningService walletProvisioningService;
    private final TransactionProvisioningService transactionProvisioningService;

    /**
     * POST /api/wallets
     * Creates a managed wallet, or returns the existing address for the same (requestId, blockchainId).
     * Example: {"requestId": "...", "blockchainId": "solana"}
     */
    @PostMapping
    public ResponseEntity<CreateWalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        String publicAddress = walletProvisioningService.createWallet(
                request.getRequestId(), request.getBlockchainId());

        return ResponseEntity.ok(CreateWalletResponse.builder()
                .requestId(request.getRequestId())
                .blockchainId(request.getBlockchainId())
                .publicAddress(publicAddress)
                .build());
    }

    /**
     * POST /api/wallets/send
     * Sends tokens from a managed wallet. Idempotent on requestId.
     * Example: {"requestId": "...", "blockchainId": "solana",
     *            "fromPublicAddress": "...", "toAddress": "...",
     *            "tokenId": "USDC", "amount": 1000000}
     */
    @PostMapping("/send")
    public ResponseEntity<SendTokenResponse> send(@Valid @RequestBody SendTokenRequest request) {
        SendTokenResponse response = transactionProvisioningService.send(
                request.getRequestId(),
                request.getBlockchainId(),
                request.getFromPublicAddress(),
                request.getToAddress(),
                request.getTokenId(),
                request.getAmount());

        return ResponseEntity.ok(response);
    }
}
