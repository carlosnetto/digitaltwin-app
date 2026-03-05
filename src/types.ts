export type CurrencyType = 'fiat' | 'crypto';

export interface Wallet {
  id: string;
  currency: string;
  name: string;
  symbol: string;
  type: CurrencyType;
  balance: number;
  logoUrl?: string | null;   // relative asset path for crypto; null/undefined for fiat
  receiveDetails: Record<string, ReceiveDetailInfo> | null;
  networks?: string[]; // For crypto
}

export type ReceiveDetailInfo =
  | { type: 'crypto'; address: string; network: string }
  | { type: 'usd'; routingNumber: string; accountNumber: string }
  | { type: 'brl'; pixKey: string };

export interface Transaction {
  id: string;
  walletId: string;
  type: 'send' | 'receive';
  amount: number;
  currency: string;
  date: string;
  status: 'completed' | 'pending' | 'failed';
  transactionCode: number;
  description: string;
  destination?: string;
  txHash?: string;
  network?: string;
}

// Transaction codes from mini-core (005-seed-data.xml)
// Credits: 10xxx | Debits: 20xxx | Fees: 30xxx
export const TX = {
  // Crypto conversions — user side (buy)
  CRYPTO_PURCHASE:              { code: 40003, label: 'Crypto Purchase' },
  // Crypto conversions — user side (sell)
  CRYPTO_SALE_PROCEEDS:         { code: 40005, label: 'Crypto Sale Proceeds' },
  // Internal transfers — liquidity pool side of conversions
  INTERNAL_TRANSFER_IN:         { code: 10018, label: 'Internal Transfer In' },
  // Credits
  DIRECT_DEPOSIT_PAYROLL:       { code: 10006, label: 'Direct Deposit - Payroll' },
  ACH_CREDIT:                   { code: 10011, label: 'ACH Credit' },
  ZELLE_RECEIVED:               { code: 10015, label: 'Zelle Received' },
  RTP_RECEIVED:                 { code: 10016, label: 'RTP Received' },
  FEDNOW_RECEIVED:              { code: 10017, label: 'FedNow Received' },
  EXTERNAL_TRANSFER_IN:         { code: 10019, label: 'External Transfer In' },
  MERCHANT_CREDIT_REFUND:       { code: 10022, label: 'Merchant Credit / Refund' },
  REWARD_CASHBACK:              { code: 10026, label: 'Reward / Cashback' },
  P2P_RECEIVED:                 { code: 10027, label: 'P2P Received' },
  // Debits
  INTERNAL_TRANSFER_OUT:        { code: 20021, label: 'Internal Transfer Out' },
  CRYPTO_PURCHASE_PAYMENT:      { code: 50005, label: 'Crypto Purchase Payment' },
  CRYPTO_SALE:                  { code: 50003, label: 'Crypto Sale' },
  DEBIT_CARD_PURCHASE:          { code: 20001, label: 'Debit Card Purchase' },
  DEBIT_CARD_PURCHASE_ONLINE:   { code: 20002, label: 'Debit Card Purchase - Online' },
  DEBIT_CARD_PURCHASE_RECURRING:{ code: 20003, label: 'Debit Card Purchase - Recurring' },
  ATM_WITHDRAWAL:               { code: 20007, label: 'ATM Withdrawal' },
  ZELLE_SENT:                   { code: 20016, label: 'Zelle Sent' },
  BILL_PAYMENT:                 { code: 20019, label: 'Bill Payment' },
  BILL_PAYMENT_RECURRING:       { code: 20020, label: 'Bill Payment - Recurring' },
  P2P_SENT:                     { code: 20026, label: 'P2P Sent' },
  LOAN_PAYMENT:                 { code: 20023, label: 'Loan Payment' },
  CREDIT_CARD_PAYMENT:          { code: 20035, label: 'Credit Card Payment' },
  // Fees
  MONTHLY_MAINTENANCE_FEE:      { code: 30001, label: 'Monthly Maintenance Fee' },
  ATM_FEE_NON_NETWORK:          { code: 30004, label: 'ATM Fee - Non-Network' },
} as const;
