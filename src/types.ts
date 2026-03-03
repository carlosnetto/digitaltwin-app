export type CurrencyType = 'fiat' | 'crypto';

export interface Wallet {
  id: string;
  currency: string;
  name: string;
  symbol: string;
  type: CurrencyType;
  balance: number;
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
  destination?: string;
  txHash?: string;
  network?: string;
}
