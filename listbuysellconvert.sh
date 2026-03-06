#!/usr/bin/env bash
# listbuysellconvert.sh — Lists all buy/sell/convert transactions with full detail:
#   user, debited currency/amount, credited currency/amount, pool tx IDs

docker exec global_banking_db psql -U admin banking_system -c "
SELECT
    cv.id,
    cv.created_at,
    u.name            AS user,
    fc.code           AS debit_currency,
    cv.from_amount    AS debit_amount,
    tc.code           AS credit_currency,
    cv.to_amount      AS credit_amount,
    cv.rate,
    cv.user_debit_tx_id,
    cv.user_credit_tx_id,
    cv.pool_credit_tx_id,
    cv.pool_debit_tx_id
FROM digitaltwinapp.conversions cv
JOIN digitaltwinapp.users      u  ON u.user_id  = cv.user_id
JOIN digitaltwinapp.currencies fc ON fc.id       = cv.from_currency_id
JOIN digitaltwinapp.currencies tc ON tc.id       = cv.to_currency_id
ORDER BY cv.id;
"
