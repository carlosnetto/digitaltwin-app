import React, { createContext, useContext, useState, ReactNode } from 'react';
import { Wallet, Transaction, ReceiveDetailInfo, TX } from './types';

interface StoreState {
  wallets: Wallet[];
  transactions: Transaction[];
  generateReceiveDetails: (walletId: string, network?: string) => void;
  sendFunds: (walletId: string, amount: number, destination: string, network?: string) => void;
}

const INITIAL_WALLETS: Wallet[] = [
  { id: '3', currency: 'USDC', name: 'USD Coin', symbol: 'USDC', type: 'crypto', balance: 250.00, receiveDetails: null, networks: ['Ethereum', 'Polygon', 'Solana'] },
  { id: '4', currency: 'USDT', name: 'Tether', symbol: 'USDT', type: 'crypto', balance: 100.00, receiveDetails: null, networks: ['Ethereum', 'Tron', 'Polygon'] },
  { id: '2', currency: 'USD', name: 'US Dollar', symbol: '$', type: 'fiat', balance: 500.00, receiveDetails: null },
  { id: '1', currency: 'BRL', name: 'Brazilian Real', symbol: 'R$', type: 'fiat', balance: 1500.50, receiveDetails: null },
];

const d = (daysAgo: number) => new Date(Date.now() - daysAgo * 86400000).toISOString();

const INITIAL_TRANSACTIONS: Transaction[] = [
  // USD wallet
  { id: 'u1',  walletId: '2', type: 'receive', amount: 3200.00, currency: 'USD', date: d(1),  status: 'completed', transactionCode: TX.DIRECT_DEPOSIT_PAYROLL.code,        description: TX.DIRECT_DEPOSIT_PAYROLL.label },
  { id: 'u2',  walletId: '2', type: 'send',    amount: 1800.00, currency: 'USD', date: d(1),  status: 'completed', transactionCode: TX.CREDIT_CARD_PAYMENT.code,            description: TX.CREDIT_CARD_PAYMENT.label },
  { id: 'u3',  walletId: '2', type: 'send',    amount: 120.00,  currency: 'USD', date: d(2),  status: 'completed', transactionCode: TX.BILL_PAYMENT_RECURRING.code,         description: TX.BILL_PAYMENT_RECURRING.label },
  { id: 'u4',  walletId: '2', type: 'send',    amount: 64.50,   currency: 'USD', date: d(3),  status: 'completed', transactionCode: TX.DEBIT_CARD_PURCHASE.code,            description: TX.DEBIT_CARD_PURCHASE.label },
  { id: 'u5',  walletId: '2', type: 'send',    amount: 39.99,   currency: 'USD', date: d(4),  status: 'completed', transactionCode: TX.DEBIT_CARD_PURCHASE_RECURRING.code,  description: TX.DEBIT_CARD_PURCHASE_RECURRING.label },
  { id: 'u6',  walletId: '2', type: 'receive', amount: 200.00,  currency: 'USD', date: d(5),  status: 'completed', transactionCode: TX.ZELLE_RECEIVED.code,                 description: TX.ZELLE_RECEIVED.label },
  { id: 'u7',  walletId: '2', type: 'send',    amount: 85.00,   currency: 'USD', date: d(6),  status: 'completed', transactionCode: TX.ZELLE_SENT.code,                     description: TX.ZELLE_SENT.label },
  { id: 'u8',  walletId: '2', type: 'send',    amount: 300.00,  currency: 'USD', date: d(7),  status: 'completed', transactionCode: TX.LOAN_PAYMENT.code,                   description: TX.LOAN_PAYMENT.label },
  { id: 'u9',  walletId: '2', type: 'send',    amount: 3.50,    currency: 'USD', date: d(8),  status: 'completed', transactionCode: TX.ATM_FEE_NON_NETWORK.code,            description: TX.ATM_FEE_NON_NETWORK.label },
  { id: 'u10', walletId: '2', type: 'send',    amount: 200.00,  currency: 'USD', date: d(8),  status: 'completed', transactionCode: TX.ATM_WITHDRAWAL.code,                 description: TX.ATM_WITHDRAWAL.label },
  { id: 'u11', walletId: '2', type: 'receive', amount: 12.50,   currency: 'USD', date: d(10), status: 'completed', transactionCode: TX.REWARD_CASHBACK.code,                description: TX.REWARD_CASHBACK.label },
  { id: 'u12', walletId: '2', type: 'send',    amount: 49.95,   currency: 'USD', date: d(12), status: 'pending',   transactionCode: TX.BILL_PAYMENT.code,                   description: TX.BILL_PAYMENT.label },

  // BRL wallet
  { id: 'b1',  walletId: '1', type: 'receive', amount: 8500.00, currency: 'BRL', date: d(1),  status: 'completed', transactionCode: TX.DIRECT_DEPOSIT_PAYROLL.code,        description: TX.DIRECT_DEPOSIT_PAYROLL.label },
  { id: 'b2',  walletId: '1', type: 'send',    amount: 350.00,  currency: 'BRL', date: d(2),  status: 'completed', transactionCode: TX.P2P_SENT.code,                      description: TX.P2P_SENT.label },
  { id: 'b3',  walletId: '1', type: 'send',    amount: 189.90,  currency: 'BRL', date: d(3),  status: 'completed', transactionCode: TX.DEBIT_CARD_PURCHASE_ONLINE.code,    description: TX.DEBIT_CARD_PURCHASE_ONLINE.label },
  { id: 'b4',  walletId: '1', type: 'receive', amount: 500.00,  currency: 'BRL', date: d(4),  status: 'completed', transactionCode: TX.P2P_RECEIVED.code,                  description: TX.P2P_RECEIVED.label },
  { id: 'b5',  walletId: '1', type: 'send',    amount: 1200.00, currency: 'BRL', date: d(5),  status: 'completed', transactionCode: TX.BILL_PAYMENT_RECURRING.code,        description: TX.BILL_PAYMENT_RECURRING.label },
  { id: 'b6',  walletId: '1', type: 'send',    amount: 67.80,   currency: 'BRL', date: d(6),  status: 'completed', transactionCode: TX.DEBIT_CARD_PURCHASE.code,           description: TX.DEBIT_CARD_PURCHASE.label },
  { id: 'b7',  walletId: '1', type: 'receive', amount: 250.00,  currency: 'BRL', date: d(8),  status: 'completed', transactionCode: TX.MERCHANT_CREDIT_REFUND.code,        description: TX.MERCHANT_CREDIT_REFUND.label },
  { id: 'b8',  walletId: '1', type: 'send',    amount: 15.90,   currency: 'BRL', date: d(10), status: 'completed', transactionCode: TX.MONTHLY_MAINTENANCE_FEE.code,       description: TX.MONTHLY_MAINTENANCE_FEE.label },

  // USDC wallet
  { id: 'c1',  walletId: '3', type: 'receive', amount: 250.00,  currency: 'USDC', date: d(3),  status: 'completed', transactionCode: TX.EXTERNAL_TRANSFER_IN.code,         description: TX.EXTERNAL_TRANSFER_IN.label,  txHash: '0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcd', network: 'Ethereum' },
  { id: 'c2',  walletId: '3', type: 'send',    amount: 50.00,   currency: 'USDC', date: d(1),  status: 'completed', transactionCode: TX.P2P_SENT.code,                     description: TX.P2P_SENT.label,              txHash: '5Kjxyz1234567890abcdef1234567890abcdef1234567890abcdef12345678', network: 'Solana' },
  { id: 'c3',  walletId: '3', type: 'receive', amount: 100.00,  currency: 'USDC', date: d(5),  status: 'completed', transactionCode: TX.MERCHANT_CREDIT_REFUND.code,       description: TX.MERCHANT_CREDIT_REFUND.label,txHash: '0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678', network: 'Polygon' },
  { id: 'c4',  walletId: '3', type: 'send',    amount: 30.00,   currency: 'USDC', date: d(7),  status: 'completed', transactionCode: TX.BILL_PAYMENT.code,                 description: TX.BILL_PAYMENT.label,          txHash: '0x9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedc', network: 'Ethereum' },
  { id: 'c5',  walletId: '3', type: 'receive', amount: 20.00,   currency: 'USDC', date: d(9),  status: 'completed', transactionCode: TX.REWARD_CASHBACK.code,              description: TX.REWARD_CASHBACK.label,       txHash: '0xdeadbeef1234567890abcdef1234567890abcdef1234567890abcdef123456', network: 'Ethereum' },

  // USDT wallet
  { id: 'd1',  walletId: '4', type: 'receive', amount: 100.00,  currency: 'USDT', date: d(4),  status: 'completed', transactionCode: TX.EXTERNAL_TRANSFER_IN.code,         description: TX.EXTERNAL_TRANSFER_IN.label,  txHash: '0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678', network: 'Polygon' },
  { id: 'd2',  walletId: '4', type: 'send',    amount: 25.00,   currency: 'USDT', date: d(2),  status: 'completed', transactionCode: TX.P2P_SENT.code,                     description: TX.P2P_SENT.label,              txHash: '0x9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedc', network: 'Ethereum' },
  { id: 'd3',  walletId: '4', type: 'send',    amount: 10.00,   currency: 'USDT', date: d(6),  status: 'completed', transactionCode: TX.DEBIT_CARD_PURCHASE_ONLINE.code,   description: TX.DEBIT_CARD_PURCHASE_ONLINE.label, txHash: '0xcafe1234567890abcdef1234567890abcdef1234567890abcdef1234567890', network: 'Tron' },
  { id: 'd4',  walletId: '4', type: 'receive', amount: 35.00,   currency: 'USDT', date: d(10), status: 'completed', transactionCode: TX.RTP_RECEIVED.code,                 description: TX.RTP_RECEIVED.label,          txHash: '0xfeed1234567890abcdef1234567890abcdef1234567890abcdef1234567890', network: 'Tron' },
];

const StoreContext = createContext<StoreState | undefined>(undefined);

export const StoreProvider = ({ children }: { children: ReactNode }) => {
  const [wallets, setWallets] = useState<Wallet[]>(INITIAL_WALLETS);
  const [transactions, setTransactions] = useState<Transaction[]>(INITIAL_TRANSACTIONS);

  const generateReceiveDetails = (walletId: string, network?: string) => {
    setWallets(prev => prev.map(wallet => {
      if (wallet.id !== walletId) return wallet;

      let newDetails: ReceiveDetailInfo;
      if (wallet.currency === 'BRL') {
        newDetails = { type: 'brl', pixKey: `+551199999${Math.floor(Math.random() * 10000)}` };
      } else if (wallet.currency === 'USD') {
        newDetails = { type: 'usd', routingNumber: '021000021', accountNumber: `123456${Math.floor(Math.random() * 10000)}` };
      } else {
        const selectedNet = network || wallet.networks![0];
        let addr = `0x${Math.random().toString(16).slice(2, 42)}`;
        if (selectedNet.toLowerCase() === 'ethereum' || selectedNet.toLowerCase() === 'polygon' || selectedNet.toLowerCase() === 'base') {
          addr = '0x98467b32997bA64ea6C4067CCDF8BDb0db1f22Bc';
        } else if (selectedNet.toLowerCase() === 'solana') {
          addr = 'D8NSFxJkf2LcF1SgCTZfe8SDrTJtdJkAeNXcQYzTk93F';
        }

        newDetails = { type: 'crypto', address: addr, network: selectedNet };
      }

      const key = wallet.type === 'crypto' ? (network || wallet.networks![0]) : 'default';
      return { ...wallet, receiveDetails: { ...(wallet.receiveDetails || {}), [key]: newDetails } };
    }));
  };

  const sendFunds = (walletId: string, amount: number, destination: string, network?: string) => {
    setWallets(prev => prev.map(wallet => {
      if (wallet.id !== walletId) return wallet;
      if (wallet.balance < amount) throw new Error('Insufficient funds');
      return { ...wallet, balance: wallet.balance - amount };
    }));

    const wallet = wallets.find(w => w.id === walletId);
    if (wallet) {
      const txType = wallet.type === 'crypto' ? TX.P2P_SENT : TX.ZELLE_SENT;
      const newTx: Transaction = {
        id: Math.random().toString(36).substr(2, 9),
        walletId,
        type: 'send',
        amount,
        currency: wallet.currency,
        date: new Date().toISOString(),
        status: 'completed',
        transactionCode: txType.code,
        description: txType.label,
        destination,
        network: wallet.type === 'crypto' ? network : undefined,
        txHash: wallet.type === 'crypto' ? `0x${Math.random().toString(16).slice(2, 66)}` : undefined
      };
      setTransactions(prev => [newTx, ...prev]);
    }
  };

  return (
    <StoreContext.Provider value={{ wallets, transactions, generateReceiveDetails, sendFunds }}>
      {children}
    </StoreContext.Provider>
  );
};

export const useStore = () => {
  const context = useContext(StoreContext);
  if (!context) throw new Error('useStore must be used within a StoreProvider');
  return context;
};
