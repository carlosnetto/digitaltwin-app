import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react';
import { Wallet, Transaction, ReceiveDetailInfo, TX } from './types';

interface StoreState {
  wallets: Wallet[];
  transactions: Transaction[];
  walletsLoading: boolean;
  refreshWallets: () => Promise<void>;
  generateReceiveDetails: (walletId: string, network?: string) => void;
  sendFunds: (walletId: string, amount: number, destination: string, network?: string) => void;
}

// Maps currency code → display symbol
const SYMBOL_MAP: Record<string, string> = {
  USD: '$', BRL: 'R$', EUR: '€', USDC: 'USDC', USDT: 'USDT',
};

// Maps currency code → supported networks (crypto only)
const NETWORKS_MAP: Record<string, string[]> = {
  USDC: ['Ethereum', 'Polygon', 'Solana'],
  USDT: ['Ethereum', 'Tron', 'Polygon'],
};

interface ApiWallet {
  id: string;
  currency: string;
  name: string;
  isFiat: boolean;
  logoUrl: string | null;
  balance: number;
  accountNumber: string;
  minicoreAccountId: number;
  decimalPlaces: number;
}

function mapApiWallet(w: ApiWallet): Wallet {
  return {
    id: w.id,
    currency: w.currency,
    name: w.name,
    symbol: SYMBOL_MAP[w.currency] ?? w.currency,
    type: w.isFiat ? 'fiat' : 'crypto',
    logoUrl: w.logoUrl,
    balance: w.balance,
    decimalPlaces: w.decimalPlaces,
    receiveDetails: null,
    networks: NETWORKS_MAP[w.currency],
  };
}

const WALLET_CACHE_KEY = 'dt_wallets';

function loadCachedWallets(): Wallet[] {
  try {
    const raw = localStorage.getItem(WALLET_CACHE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export function clearWalletCache() {
  localStorage.removeItem(WALLET_CACHE_KEY);
}

const StoreContext = createContext<StoreState | undefined>(undefined);

export const StoreProvider = ({ children }: { children: ReactNode }) => {
  const cached = loadCachedWallets();
  const [wallets, setWallets] = useState<Wallet[]>(cached);
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [walletsLoading, setWalletsLoading] = useState(cached.length === 0);

  const refreshWallets = useCallback(async () => {
    setWalletsLoading(true);
    try {
      const res = await fetch(`${import.meta.env.BASE_URL}api/wallets`, {
        credentials: 'include',
      });
      if (!res.ok) return;
      const data: ApiWallet[] = await res.json();
      const mapped = data.map(mapApiWallet);
      setWallets(mapped);
      localStorage.setItem(WALLET_CACHE_KEY, JSON.stringify(mapped));
    } catch {
      // Leave wallets as-is on network error (cached data still shown)
    } finally {
      setWalletsLoading(false);
    }
  }, []);

  // Load wallets on mount — covers both fresh login and page refresh with existing session
  useEffect(() => {
    refreshWallets();
  }, [refreshWallets]);

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
        if (['ethereum', 'polygon', 'base'].includes(selectedNet.toLowerCase())) {
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
        txHash: wallet.type === 'crypto' ? `0x${Math.random().toString(16).slice(2, 66)}` : undefined,
      };
      setTransactions(prev => [newTx, ...prev]);
    }
  };

  return (
    <StoreContext.Provider value={{ wallets, transactions, walletsLoading, refreshWallets, generateReceiveDetails, sendFunds }}>
      {children}
    </StoreContext.Provider>
  );
};

export const useStore = () => {
  const context = useContext(StoreContext);
  if (!context) throw new Error('useStore must be used within a StoreProvider');
  return context;
};
