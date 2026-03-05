package com.matera.digitaltwin.api.controller;

import com.matera.digitaltwin.api.model.UserInfo;
import com.matera.digitaltwin.api.model.WalletDto;
import com.matera.digitaltwin.api.service.WalletService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
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
}
