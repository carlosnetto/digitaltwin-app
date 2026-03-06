#!/usr/bin/env bash
# listp2ptrans.sh — Lists all P2P transactions with sender, recipient, amount, and currency

docker exec global_banking_db psql -U admin banking_system -c "
SELECT
    p.id,
    p.created_at,
    su.name  AS sender,
    ru.name  AS recipient,
    p.amount,
    c.code   AS currency
FROM digitaltwinapp.p2p_transactions p
JOIN minicore.transactions   dt  ON dt.transaction_id      = p.debit_tx_id
JOIN digitaltwinapp.user_accounts dua ON dua.minicore_account_id = dt.account_id
JOIN digitaltwinapp.users    su  ON su.user_id             = dua.user_id
JOIN minicore.transactions   ct  ON ct.transaction_id      = p.credit_tx_id
JOIN digitaltwinapp.user_accounts cua ON cua.minicore_account_id = ct.account_id
JOIN digitaltwinapp.users    ru  ON ru.user_id             = cua.user_id
JOIN digitaltwinapp.currencies c ON c.id                  = dua.currency_id
ORDER BY p.id;
"
