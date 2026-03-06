package com.matera.digitaltwin.api.controller;

import com.matera.digitaltwin.api.model.ConversionRequest;
import com.matera.digitaltwin.api.model.ConversionResultDto;
import com.matera.digitaltwin.api.model.UserInfo;
import com.matera.digitaltwin.api.model.WalletDto;
import com.matera.digitaltwin.api.service.ConversionService;
import com.matera.digitaltwin.api.service.WalletService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final WalletService      walletService;
    private final ConversionService  conversionService;

    public WalletController(WalletService walletService, ConversionService conversionService) {
        this.walletService     = walletService;
        this.conversionService = conversionService;
    }

    @GetMapping("/api/wallets")
    public ResponseEntity<?> getWallets(HttpSession session) {
        UserInfo user = (UserInfo) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }
        List<WalletDto> wallets = walletService.getWalletsForUser(user.email());
        return ResponseEntity.ok(wallets);
    }

    @GetMapping("/api/wallets/transactions")
    public ResponseEntity<?> getTransactions(@RequestParam String currencyCode, HttpSession session) {
        UserInfo user = (UserInfo) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        return ResponseEntity.ok(walletService.getTransactions(user.email(), currencyCode));
    }

    @GetMapping("/api/wallets/rate")
    public ResponseEntity<?> getRate(@RequestParam String from, @RequestParam String to,
                                     HttpSession session) {
        if (session.getAttribute("user") == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        try {
            java.math.BigDecimal rate = walletService.getRate(from, to);
            return ResponseEntity.ok(Map.of("rate", rate));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "No rate found for " + from + " → " + to));
        }
    }

    @PostMapping("/api/wallets/convert")
    public ResponseEntity<?> convert(@RequestBody ConversionRequest req, HttpSession session) {
        UserInfo user = (UserInfo) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }
        if (req.fromAmount() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Amount must be greater than zero"));
        }
        try {
            ConversionResultDto result = conversionService.convert(
                    user.email(), req.fromCurrencyCode(), req.toCurrencyCode(), req.fromAmount());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Conversion failed for user={}: {}", user.email(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Conversion failed: " + e.getMessage()));
        }
    }
}
