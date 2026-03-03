import React, { createContext, useContext, useState, ReactNode } from 'react';
import { Wallet, Transaction, ReceiveDetailInfo } from './types';

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

const INITIAL_TRANSACTIONS: Transaction[] = [
  { id: 't3', walletId: '3', type: 'receive', amount: 250.00, currency: 'USDC', date: new Date(Date.now() - 259200000).toISOString(), status: 'completed', destination: '0x123...abc', txHash: '0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef', network: 'Ethereum' },
  { id: 't3_send', walletId: '3', type: 'send', amount: 50.00, currency: 'USDC', date: new Date(Date.now() - 86400000).toISOString(), status: 'completed', destination: '0x999...xyz', txHash: '5Kjxyz1234567890abcdef1234567890abcdef1234567890abcdef1234567890', network: 'Solana' },
  { id: 't4', walletId: '4', type: 'receive', amount: 100.00, currency: 'USDT', date: new Date(Date.now() - 345600000).toISOString(), status: 'completed', destination: '0x456...def', txHash: '0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678', network: 'Polygon' },
  { id: 't4_send', walletId: '4', type: 'send', amount: 25.00, currency: 'USDT', date: new Date(Date.now() - 172800000).toISOString(), status: 'completed', destination: '0x888...uvw', txHash: '0x9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedc', network: 'Ethereum' },
  { id: 't2', walletId: '2', type: 'receive', amount: 500.00, currency: 'USD', date: new Date(Date.now() - 172800000).toISOString(), status: 'completed', destination: 'ACH Transfer' },
  { id: 't2_send', walletId: '2', type: 'send', amount: 120.00, currency: 'USD', date: new Date(Date.now() - 43200000).toISOString(), status: 'completed', destination: 'Wire Transfer' },
  { id: 't1', walletId: '1', type: 'receive', amount: 1500.50, currency: 'BRL', date: new Date(Date.now() - 86400000).toISOString(), status: 'completed', destination: 'Pix Transfer' },
  { id: 't1_send', walletId: '1', type: 'send', amount: 350.00, currency: 'BRL', date: new Date(Date.now() - 36000000).toISOString(), status: 'completed', destination: 'Pix to John Doe' },
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
      const newTx: Transaction = {
        id: Math.random().toString(36).substr(2, 9),
        walletId,
        type: 'send',
        amount,
        currency: wallet.currency,
        date: new Date().toISOString(),
        status: 'completed',
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
