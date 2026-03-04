package com.matera.digitaltwin.backoffice.controller;

import com.matera.digitaltwin.backoffice.adaptor.circle.CircleMintAdaptor;
import com.matera.digitaltwin.backoffice.dto.request.CircleBurnRequest;
import com.matera.digitaltwin.backoffice.dto.request.CircleMintRequest;
import com.matera.digitaltwin.backoffice.dto.response.BalanceResponse;
import com.matera.digitaltwin.backoffice.dto.response.CircleOperationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/circle")
@RequiredArgsConstructor
public class CircleController {

    private final CircleMintAdaptor circleMintAdaptor;

    /** POST /api/circle/mint — mint USDC via Circle. */
    @PostMapping("/mint")
    public ResponseEntity<CircleOperationResponse> mint(
            @Valid @RequestBody CircleMintRequest request) {
        return ResponseEntity.ok(circleMintAdaptor.mint(request));
    }

    /** POST /api/circle/burn — burn/redeem USDC via Circle. */
    @PostMapping("/burn")
    public ResponseEntity<CircleOperationResponse> burn(
            @Valid @RequestBody CircleBurnRequest request) {
        return ResponseEntity.ok(circleMintAdaptor.burn(request));
    }

    /** GET /api/circle/account/{accountId}/balance — Circle account balance. */
    @GetMapping("/account/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String accountId) {
        return ResponseEntity.ok(circleMintAdaptor.getAccountBalance(accountId));
    }

    /** GET /api/circle/account/{accountId}/mints — list mint operations. */
    @GetMapping("/account/{accountId}/mints")
    public ResponseEntity<List<CircleOperationResponse>> listMints(
            @PathVariable String accountId) {
        return ResponseEntity.ok(circleMintAdaptor.listMints(accountId));
    }

    /** GET /api/circle/operation/{operationId} — poll operation status. */
    @GetMapping("/operation/{operationId}")
    public ResponseEntity<CircleOperationResponse> getOperation(
            @PathVariable String operationId) {
        return ResponseEntity.ok(circleMintAdaptor.getOperationStatus(operationId));
    }
}
