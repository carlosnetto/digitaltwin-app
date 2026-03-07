package com.matera.digitaltwin.api.controller;

import com.matera.digitaltwin.api.model.ConversionRequest;
import com.matera.digitaltwin.api.model.P2pRequest;
import com.matera.digitaltwin.api.model.ConversionResultDto;
import com.matera.digitaltwin.api.model.UserInfo;
import com.matera.digitaltwin.api.model.WalletDto;
import com.matera.digitaltwin.api.service.ConversionService;
import com.matera.digitaltwin.api.service.StatementService;
import com.matera.digitaltwin.api.service.TransactionDisplayService;
import com.matera.digitaltwin.api.service.WalletService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final WalletService      walletService;
    private final ConversionService  conversionService;
    private final StatementService   statementService;

    public WalletController(WalletService walletService,
                            ConversionService conversionService,
                            StatementService statementService) {
        this.walletService     = walletService;
        this.conversionService = conversionService;
        this.statementService  = statementService;
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

    /**
     * Returns up to 50 recent transactions, each optionally enriched with a
     * human-readable {@code summary} string resolved from transaction_metadata.
     *
     * @param currencyCode the wallet to query (e.g. "USD", "USDC")
     * @param lang         BCP-47 language tag for the summary string (default: "en")
     */
    @GetMapping("/api/wallets/transactions")
    public ResponseEntity<?> getTransactions(@RequestParam String currencyCode,
                                             @RequestParam(defaultValue = "en") String lang,
                                             HttpSession session) {
        UserInfo user = (UserInfo) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        return ResponseEntity.ok(walletService.getTransactions(user.email(), currencyCode, lang));
    }

    /**
     * Returns the full detail display for a single transaction — summary + labeled fields.
     * Called when the user taps a transaction row. One SQL query against transaction_metadata;
     * no batch needed since only one transaction is requested at a time.
     *
     * Returns 404 if no metadata exists for this transaction (e.g. a deposit code that
     * does not carry extra metadata). The UI should fall back to showing the raw ledger data.
     *
     * @param ledgerId mini-core transaction_id (numeric, passed as a string in the path)
     * @param lang     BCP-47 language tag (default: "en")
     */
    @GetMapping("/api/wallets/transactions/{ledgerId}")
    public ResponseEntity<?> getTransactionDetail(@PathVariable String ledgerId,
                                                  @RequestParam(defaultValue = "en") String lang,
                                                  HttpSession session) {
        if (session.getAttribute("user") == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        Optional<TransactionDisplayService.ResolvedDisplay> detail =
                walletService.getTransactionDetail(ledgerId, lang);

        return detail
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No metadata for transaction " + ledgerId)));
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

    @GetMapping("/api/users/lookup")
    public ResponseEntity<?> lookupUser(@RequestParam String email, HttpSession session) {
        if (session.getAttribute("user") == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        String name = walletService.lookupUserName(email);
        if (name == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }
        return ResponseEntity.ok(Map.of("name", name));
    }

    /**
     * Streams an account statement as a PDF directly to the response — no temp file written.
     * The browser (or mobile OS PDF viewer) handles save / share / print natively.
     *
     * @param currencyCode e.g. "USD", "USDC"
     * @param from         ISO date "yyyy-MM-dd" (inclusive)
     * @param to           ISO date "yyyy-MM-dd" (inclusive)
     * @param lang         BCP-47 language tag for i18n summaries (default: "en")
     */
    @GetMapping(value = "/api/wallets/statement", produces = "application/pdf")
    public void getStatement(@RequestParam String currencyCode,
                             @RequestParam String from,
                             @RequestParam String to,
                             @RequestParam(defaultValue = "en") String lang,
                             HttpSession session,
                             HttpServletResponse response) throws Exception {
        UserInfo user = (UserInfo) session.getAttribute("user");
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate   = LocalDate.parse(to);
        if (fromDate.isAfter(toDate)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String filename = "statement-" + currencyCode + "-" + from + "-" + to + ".pdf";
        response.setContentType("application/pdf");
        // inline → browser opens PDF viewer (mobile: share sheet; desktop: in-browser viewer)
        response.setHeader("Content-Disposition", "inline; filename=\"" + filename + "\"");

        try {
            statementService.generate(user.email(), currencyCode, fromDate, toDate, lang,
                    response.getOutputStream());
        } catch (IllegalArgumentException e) {
            log.warn("getStatement: bad request: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        } catch (Exception e) {
            log.error("getStatement: PDF generation failed: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/api/wallets/p2p")
    public ResponseEntity<?> p2pTransfer(@RequestBody P2pRequest req, HttpSession session) {
        UserInfo user = (UserInfo) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        if (req.amount() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Amount must be greater than zero"));
        }
        try {
            Map<String, Object> result = walletService.p2pTransfer(
                    user.email(), req.recipientEmail(), req.currencyCode(), req.amount());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("P2P transfer failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Transfer failed: " + e.getMessage()));
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
