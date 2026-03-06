package com.matera.digitaltwin.api.model;

/**
 * Wallet response returned by GET /api/wallets.
 * Combines currency metadata from digitaltwinapp.currencies with
 * the live available_balance fetched from mini-core.
 */
public record WalletDto(
        String id,               // currency_id as string — used as the wallet identifier in the frontend
        String currency,         // currency code: USD, BRL, USDC, USDT, …
        String name,             // display name: "US Dollar", "Brazilian Real", …
        boolean isFiat,          // true → fiat (Convert action), false → crypto (Buy/Sell actions)
        String logoUrl,          // relative asset path for crypto logos; null for fiat (flag rendered by frontend)
        double balance,          // available_balance from mini-core
        String accountNumber,    // account number in mini-core (user_id * 1000 + currency_id)
        long minicoreAccountId,  // account_id in mini-core — for future transaction API calls
        int decimalPlaces        // number of decimal places for this currency (e.g. 2 for USD/BRL, 6 for USDC/USDT)
) {}
