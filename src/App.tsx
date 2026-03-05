import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useGoogleLogin } from '@react-oauth/google';
import { StoreProvider, useStore } from './store';
import { Wallet as WalletType } from './types';
import { Wallet, ArrowDown, ArrowUp, ArrowDownLeft, ArrowUpRight, Plus, Minus, Repeat, Activity, Settings, Home, X, Copy, CheckCircle2, AlertCircle, ChevronLeft, ChevronRight, QrCode } from 'lucide-react';
import QRCode from 'react-qr-code';

// Simple Toast component
function Toast({ message, visible }: { message: string, visible: boolean }) {
  if (!visible) return null;
  return (
    <div className="fixed top-4 left-1/2 -translate-x-1/2 z-[100] bg-matera-green text-matera-blue-dark px-4 py-2 rounded-full font-medium shadow-lg animate-in slide-in-from-top-4 fade-in">
      {message}
    </div>
  );
}

function CurrencyIcon({ currency, symbol }: { currency: string, symbol: string }) {
  if (currency === 'USDC') {
    return (
      <img
        src={`${import.meta.env.BASE_URL}assets/Circle_USDC_Logo.svg.png`}
        alt="USDC"
        className="w-full h-full object-cover"
      />
    );
  }
  if (currency === 'USDT') {
    return (
      <img
        src={`${import.meta.env.BASE_URL}assets/tether-usdt-logo.svg`}
        alt="USDT"
        className="w-full h-full object-cover"
      />
    );
  }
  if (currency === 'USD') {
    return (
      <svg className="w-full h-full object-cover" viewBox="0 0 512 512" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="256" cy="256" r="256" fill="#F0F0F0" />
        <g clipPath="url(#clip0_usd_flag)">
          <path d="M0 42.667v426.666h512V42.667H0z" fill="#D80027" />
          <path d="M0 113.778h512v42.666H0v-42.666zm0 85.333h512v42.667H0v-42.667zm0 85.333h512v42.667H0v-42.667zm0 85.334h512V412.44H0v-42.663z" fill="#EEEEEE" />
          <path d="M0 42.667h256V256H0V42.667z" fill="#0052B4" />
          <path fill="#EEEEEE" d="M16 64h22v22H16zm42 0h22v22H58zm43 0h22v22h-22zm42 0h21v22h-21zm43 0h22v22h-22zm-149 42h22v22H37zm42 0h22v22H79zm43 0h22v22h-22zm42 0h21v22h-21zm43 0h22v22h-22zm-149 43h22v22H16zm42 0h22v22H58zm43 0h22v22h-22zm42 0h21v22h-21zm43 0h22v22h-22zm-149 43h22v22H37zm42 0h22v22H79zm43 0h22v22h-22zm42 0h21v22h-21zm43 0h22v22h-22zm-149 42h22v22H16zm42 0h22v22H58zm43 0h22v22h-22zm42 0h21v22h-21zm43 0h22v22h-22z" />
        </g>
        <defs>
          <clipPath id="clip0_usd_flag">
            <circle cx="256" cy="256" r="256" />
          </clipPath>
        </defs>
      </svg>
    );
  }
  if (currency === 'BRL') {
    return (
      <svg className="w-full h-full object-cover scale-110" viewBox="0 0 512 512" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="256" cy="256" r="256" fill="#009B3A" />
        <path d="M256 70L440 256L256 442L72 256L256 70Z" fill="#FEDF00" />
        <circle cx="256" cy="256" r="115" fill="#002776" />
        <path d="M165 256 C210 206 302 206 347 256" stroke="#FFFFFF" strokeWidth="14" fill="none" strokeLinecap="round" />
      </svg>
    );
  }
  return <span className="font-bold text-matera-green">{symbol}</span>;
}

function Dashboard() {
  const { wallets, transactions } = useStore();
  const [selectedWallet, setSelectedWallet] = useState<WalletType | null>(null);
  const [actionType, setActionType] = useState<'send' | 'receive' | 'buy' | 'sell' | 'convert' | null>(null);
  const [currentIndex, setCurrentIndex] = useState(0);
  const scrollRef = useRef<HTMLDivElement>(null);

  const getExplorerUrl = (network: string, txHash: string) => {
    switch (network.toLowerCase()) {
      case 'ethereum': return `https://etherscan.io/tx/${txHash}`;
      case 'polygon': return `https://polygonscan.com/tx/${txHash}`;
      case 'solana': return `https://solscan.io/tx/${txHash}`;
      case 'base': return `https://basescan.org/tx/${txHash}`;
      case 'tron': return `https://tronscan.org/#/transaction/${txHash}`;
      default: return '#';
    }
  };

  const handleScroll = () => {
    if (!scrollRef.current) return;
    const scrollLeft = scrollRef.current.scrollLeft;
    const width = scrollRef.current.clientWidth;
    const index = Math.round(scrollLeft / width);
    if (index !== currentIndex) {
      setCurrentIndex(index);
    }
  };

  const scrollPrev = () => {
    if (scrollRef.current) {
      const width = scrollRef.current.clientWidth;
      scrollRef.current.scrollBy({ left: -width, behavior: 'smooth' });
    }
  };

  const scrollNext = () => {
    if (scrollRef.current) {
      const width = scrollRef.current.clientWidth;
      scrollRef.current.scrollBy({ left: width, behavior: 'smooth' });
    }
  };

  const activeWallet = wallets[currentIndex];
  const activeTransactions = transactions.filter(t => t.walletId === activeWallet.id).sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime());

  return (
    <div className="max-w-4xl mx-auto py-2 md:py-4">
      <header className="flex justify-between items-center mb-4 px-4 md:px-8">
        <div className="md:hidden">
          <img
            src="https://d2lq74zxbg4jiz.cloudfront.net/matera_logo_8e3c7e36a1.svg"
            alt="Matera"
            className="h-4 object-contain"
          />
        </div>
        <div className="w-10 h-10 rounded-full bg-matera-blue flex items-center justify-center border border-matera-green/30">
          <span className="font-bold text-matera-green">CN</span>
        </div>
      </header>

      <div className="relative group">
        {/* Desktop Navigation Arrows */}
        <button
          onClick={scrollPrev}
          className={`hidden md:flex absolute left-2 top-1/2 -translate-y-1/2 z-20 w-10 h-10 bg-matera-card/80 backdrop-blur border border-white/10 rounded-full items-center justify-center text-white hover:bg-matera-card transition-all ${currentIndex === 0 ? 'opacity-0 pointer-events-none' : 'opacity-0 group-hover:opacity-100'}`}
        >
          <ChevronLeft size={24} />
        </button>
        <button
          onClick={scrollNext}
          className={`hidden md:flex absolute right-2 top-1/2 -translate-y-1/2 z-20 w-10 h-10 bg-matera-card/80 backdrop-blur border border-white/10 rounded-full items-center justify-center text-white hover:bg-matera-card transition-all ${currentIndex === wallets.length - 1 ? 'opacity-0 pointer-events-none' : 'opacity-0 group-hover:opacity-100'}`}
        >
          <ChevronRight size={24} />
        </button>

        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className="flex overflow-x-auto snap-x snap-mandatory scroll-smooth touch-pan-x no-scrollbar pb-4"
        >
          {wallets.map((wallet, index) => (
            <div key={wallet.id} className="min-w-full snap-center px-4 md:px-8">
              <section className="bg-gradient-to-br from-matera-blue to-matera-blue-dark rounded-3xl p-4 border border-white/5 shadow-xl relative overflow-hidden h-full">
                <div className="absolute top-0 right-0 w-64 h-64 bg-matera-green/10 rounded-full blur-3xl -mr-20 -mt-20"></div>
                <div className="relative z-10">
                  <div className="flex justify-between items-center mb-3">
                    <div className="flex items-center gap-3">
                      <div className="w-8 h-8 rounded-full flex items-center justify-center overflow-hidden border border-white/10 shrink-0">
                        <div className="w-full h-full object-cover rounded-full">
                          <CurrencyIcon currency={wallet.currency} symbol={wallet.symbol} />
                        </div>
                      </div>
                      <div>
                        <h3 className="text-white font-semibold text-lg">{wallet.name}</h3>
                        <p className="text-matera-muted text-sm">{wallet.currency}</p>
                      </div>
                    </div>
                    <div className="bg-white/10 px-3 py-1 rounded-full text-xs font-medium text-matera-muted">
                      {index + 1} of {wallets.length}
                    </div>
                  </div>

                  <p className="text-matera-muted mb-0 text-sm">Available Balance</p>
                  <h2 className="text-3xl md:text-4xl font-bold text-white mb-4">
                    {wallet.balance.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                  </h2>

                  <div className="flex gap-2 sm:gap-4">
                    <button
                      onClick={() => { setSelectedWallet(wallet); setActionType('receive'); }}
                      className="flex-1 bg-matera-green text-matera-blue-dark font-semibold py-2 px-1 sm:px-2 rounded-xl flex items-center justify-center gap-1 sm:gap-2 hover:bg-matera-green-dark transition-colors text-xs sm:text-sm"
                    >
                      <ArrowDown className="shrink-0" size={16} /> <span className="truncate">Receive</span>
                    </button>
                    <button
                      onClick={() => { setSelectedWallet(wallet); setActionType('send'); }}
                      className="flex-1 bg-white/10 text-white font-semibold py-2 px-1 sm:px-2 rounded-xl flex items-center justify-center gap-1 sm:gap-2 hover:bg-white/20 transition-colors border border-white/10 text-xs sm:text-sm"
                    >
                      <ArrowUp className="shrink-0" size={16} /> <span className="truncate">Send</span>
                    </button>
                    {wallet.type === 'crypto' ? (
                      <>
                        <button
                          onClick={() => { setSelectedWallet(wallet); setActionType('buy'); }}
                          className="flex-1 bg-white/10 text-white font-semibold py-2 px-1 sm:px-2 rounded-xl flex items-center justify-center gap-1 sm:gap-2 hover:bg-white/20 transition-colors border border-white/10 text-xs sm:text-sm"
                        >
                          <Plus className="shrink-0" size={16} /> <span className="truncate">Buy</span>
                        </button>
                        <button
                          onClick={() => { setSelectedWallet(wallet); setActionType('sell'); }}
                          className="flex-1 bg-white/10 text-white font-semibold py-2 px-1 sm:px-2 rounded-xl flex items-center justify-center gap-1 sm:gap-2 hover:bg-white/20 transition-colors border border-white/10 text-xs sm:text-sm"
                        >
                          <Minus className="shrink-0" size={16} /> <span className="truncate">Sell</span>
                        </button>
                      </>
                    ) : (
                      <button
                        onClick={() => { setSelectedWallet(wallet); setActionType('convert'); }}
                        className="flex-1 bg-white/10 text-white font-semibold py-2 px-1 sm:px-2 rounded-xl flex items-center justify-center gap-1 sm:gap-2 hover:bg-white/20 transition-colors border border-white/10 text-xs sm:text-sm"
                      >
                        <Repeat className="shrink-0" size={16} /> <span className="truncate">Convert</span>
                      </button>
                    )}
                  </div>
                </div>
              </section>
            </div>
          ))}
        </div>

        {/* Dots indicator */}
        <div className="flex justify-center gap-2 mt-2">
          {wallets.map((_, idx) => (
            <div
              key={idx}
              className={`h-1.5 rounded-full transition-all duration-300 ${idx === currentIndex ? 'w-6 bg-matera-green' : 'w-1.5 bg-white/20'}`}
            />
          ))}
        </div>
      </div>

      <section className="mt-4 px-4 md:px-8">
        <div className="flex justify-between items-center mb-2">
          <h3 className="text-lg font-semibold text-white">Recent Transactions</h3>
          <span className="text-sm text-matera-muted">{activeWallet.currency}</span>
        </div>

        {activeTransactions.length > 0 ? (
          <div className="space-y-2">
            {activeTransactions.map(tx => (
              <div key={tx.id} className="bg-matera-card rounded-xl p-2 border border-white/5 flex justify-between items-center hover:border-white/10 transition-colors">
                <div className="flex items-center gap-3">
                  <div className={`w-8 h-8 rounded-full flex items-center justify-center ${tx.type === 'receive' ? 'bg-matera-green/10 text-matera-green' : 'bg-white/5 text-white'}`}>
                    {tx.type === 'receive' ? <ArrowDownLeft size={16} /> : <ArrowUpRight size={16} />}
                  </div>
                  <div>
                    <p className="text-white font-medium">{tx.description}</p>
                    <p className="text-xs text-matera-muted">{new Date(tx.date).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}</p>
                    {/* 
                      FEATURE: Multi-chain Off-chain Balances
                      Since the user's balance is maintained off-chain in the Digital Twin Account,
                      a single asset like USDC can have underlying on-chain transactions across
                      multiple different networks (Ethereum, Solana, Base, etc.).
                      We display the specific network's block explorer link for each crypto transaction.
                    */}
                    {tx.txHash && tx.network && (
                      <a
                        href={getExplorerUrl(tx.network, tx.txHash)}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-[10px] text-matera-green hover:underline flex items-center gap-1 mt-1"
                      >
                        Tx: {tx.txHash.substring(0, 6)}...{tx.txHash.substring(tx.txHash.length - 4)}
                        <ArrowUpRight size={10} />
                      </a>
                    )}
                  </div>
                </div>
                <div className="text-right">
                  <p className={`font-semibold ${tx.type === 'receive' ? 'text-matera-green' : 'text-red-400'}`}>
                    {tx.type === 'receive' ? '+' : '-'}
                    {tx.amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                  </p>
                  <p className="text-xs text-matera-muted capitalize">{tx.status}</p>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="bg-matera-card rounded-2xl p-8 border border-white/5 text-center">
            <div className="w-12 h-12 bg-white/5 rounded-full flex items-center justify-center mx-auto mb-3 text-matera-muted">
              <Activity size={24} />
            </div>
            <p className="text-white font-medium">No transactions yet</p>
            <p className="text-sm text-matera-muted mt-1">When you send or receive {activeWallet.currency}, it will show up here.</p>
          </div>
        )}
      </section>

      {selectedWallet && actionType === 'receive' && (
        <ReceiveModal walletId={selectedWallet.id} onClose={() => { setSelectedWallet(null); setActionType(null); }} />
      )}

      {selectedWallet && actionType === 'send' && (
        <SendModal wallet={selectedWallet} onClose={() => { setSelectedWallet(null); setActionType(null); }} />
      )}
      {selectedWallet && actionType === 'buy' && (
        <BuyModal wallet={selectedWallet} onClose={() => { setSelectedWallet(null); setActionType(null); }} />
      )}
      {selectedWallet && actionType === 'sell' && (
        <SellModal wallet={selectedWallet} onClose={() => { setSelectedWallet(null); setActionType(null); }} />
      )}
      {selectedWallet && actionType === 'convert' && (
        <ConvertModal wallet={selectedWallet} onClose={() => { setSelectedWallet(null); setActionType(null); }} />
      )}
    </div>
  );
}

function ConvertModal({ wallet, onClose }: { wallet: WalletType, onClose: () => void }) {
  const { wallets } = useStore();
  const [sourceAmount, setSourceAmount] = useState('');
  const [targetAmount, setTargetAmount] = useState('');

  // Exclude current wallet from available target options
  const targetOptions = wallets.filter(w => w.id !== wallet.id);
  const [targetWalletId, setTargetWalletId] = useState(targetOptions[0]?.id || '');
  const [success, setSuccess] = useState(false);

  // Hardcode exchange rates relative to USD for simplicity in the prototype
  // 1 USD = 5 BRL, 1 USD = 1 USDC, 1 USD = 1 USDT
  const getRateToUSD = (currency: string) => {
    switch (currency) {
      case 'BRL': return 0.2; // 1 BRL = 0.2 USD
      case 'USDC': return 1;
      case 'USDT': return 1;
      case 'USD': default: return 1;
    }
  };

  const calculateRate = (sourceCurr: string, targetCurr: string) => {
    const sourceToUsd = getRateToUSD(sourceCurr);
    const targetToUsd = getRateToUSD(targetCurr);
    return sourceToUsd / targetToUsd;
  };

  const targetWallet = wallets.find(w => w.id === targetWalletId);
  const rate = targetWallet ? calculateRate(wallet.currency, targetWallet.currency) : 1;

  const handleSourceChange = (val: string) => {
    setSourceAmount(val);
    const num = parseFloat(val);
    if (!isNaN(num)) {
      setTargetAmount((num * rate).toString());
    } else {
      setTargetAmount('');
    }
  };

  const handleTargetChange = (val: string) => {
    setTargetAmount(val);
    const num = parseFloat(val);
    if (!isNaN(num)) {
      setSourceAmount((num / rate).toString());
    } else {
      setSourceAmount('');
    }
  };

  const handleTargetWalletChange = (val: string) => {
    setTargetWalletId(val);
    const newTarget = wallets.find(w => w.id === val);
    if (newTarget) {
      const newRate = calculateRate(wallet.currency, newTarget.currency);
      const num = parseFloat(sourceAmount);
      if (!isNaN(num)) {
        setTargetAmount((num * newRate).toString());
      }
    }
  };

  const handleConvert = () => {
    setSuccess(true);
    setTimeout(onClose, 2000);
  };

  if (success) {
    return (
      <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
        <div className="bg-matera-card w-full max-w-sm rounded-3xl border border-white/10 p-8 text-center animate-in zoom-in-95">
          <CheckCircle2 size={64} className="text-matera-green mx-auto mb-4" />
          <h3 className="text-2xl font-bold text-white mb-2">Conversion Successful</h3>
          <p className="text-matera-muted">Your funds have been converted and updated in your Digital Twin Account.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-end md:items-center justify-center z-50 p-4">
      <div className="bg-matera-card w-full max-w-md rounded-3xl border border-white/10 overflow-hidden animate-in slide-in-from-bottom-8 md:slide-in-from-bottom-0 md:zoom-in-95">
        <div className="p-6">
          <div className="flex justify-between items-center mb-6">
            <h3 className="text-xl font-bold text-white">Convert {wallet.currency}</h3>
            <button onClick={onClose} className="text-matera-muted hover:text-white p-1"><X size={24} /></button>
          </div>

          <div className="space-y-6">
            <div>
              <label className="block text-sm font-medium text-matera-muted mb-2">I want to convert</label>
              <div className="relative">
                <input
                  type="number"
                  value={sourceAmount}
                  onChange={(e) => handleSourceChange(e.target.value)}
                  placeholder="0.00"
                  className="w-full bg-matera-bg border border-white/10 rounded-xl py-4 pl-4 pr-16 text-2xl font-semibold text-white focus:outline-none focus:border-matera-green"
                />
                <div className="absolute inset-y-0 right-4 flex items-center pointer-events-none">
                  <span className="text-white font-medium">{wallet.currency}</span>
                </div>
              </div>
              <p className="text-xs text-matera-muted mt-1 text-right">Available: {wallet.balance}</p>
            </div>

            <div className="flex justify-center -my-2 relative z-10">
              <div className="bg-matera-blue w-8 h-8 rounded-full flex items-center justify-center border border-white/10 text-matera-muted">
                <Repeat size={16} />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-matera-muted mb-2">I will receive</label>
              <div className="flex gap-2">
                <div className="relative flex-1">
                  <input
                    type="number"
                    value={targetAmount}
                    onChange={(e) => handleTargetChange(e.target.value)}
                    placeholder="0.00"
                    className="w-full h-full bg-matera-bg border border-white/10 rounded-xl py-3 pl-4 pr-4 text-xl font-semibold text-white focus:outline-none focus:border-matera-green"
                  />
                </div>
                <select
                  value={targetWalletId}
                  onChange={(e) => handleTargetWalletChange(e.target.value)}
                  className="w-32 bg-matera-bg border border-white/10 rounded-xl p-3 text-white focus:outline-none focus:border-matera-green font-medium"
                >
                  {targetOptions.map(w => (
                    <option key={w.id} value={w.id}>{w.currency}</option>
                  ))}
                </select>
              </div>
              <p className="text-xs text-matera-muted mt-2 text-right">
                Exchange Rate: 1 {wallet.currency} = {rate} {targetWallet?.currency}
              </p>
            </div>

            {(parseFloat(sourceAmount) > wallet.balance) && (
              <p className="text-red-400 text-sm text-center -mt-2">Insufficient funds for this conversion.</p>
            )}

            <button
              onClick={handleConvert}
              disabled={!sourceAmount || parseFloat(sourceAmount) <= 0 || parseFloat(sourceAmount) > wallet.balance}
              className="w-full bg-matera-green text-matera-blue-dark font-semibold py-4 px-4 rounded-xl disabled:opacity-50 disabled:cursor-not-allowed hover:bg-matera-green-dark transition-colors mt-2 text-lg shadow-lg"
            >
              Confirm Conversion
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}



function ReceiveModal({ walletId, onClose }: { walletId: string, onClose: () => void }) {
  const { wallets, generateReceiveDetails } = useStore();
  const wallet = wallets.find(w => w.id === walletId)!;
  const [agreed, setAgreed] = useState(false);
  const [selectedNetwork, setSelectedNetwork] = useState(wallet.networks?.[0] || '');
  const [toastVisible, setToastVisible] = useState(false);

  // We only ask for T&C if it's Ethereum and not yet agreed in this session
  const requiresTC = wallet.type === 'crypto' && selectedNetwork.toLowerCase() === 'ethereum' && !agreed;
  const currentDetails = wallet.receiveDetails ? wallet.receiveDetails[wallet.type === 'crypto' ? selectedNetwork : 'default'] : null;

  useEffect(() => {
    if (!currentDetails && !requiresTC) {
      generateReceiveDetails(wallet.id, selectedNetwork);
    }
  }, [wallet.id, selectedNetwork, currentDetails, requiresTC, generateReceiveDetails]);

  const handleGenerate = () => {
    setAgreed(true);
  };

  const getQRValue = () => {
    if (!currentDetails) return '';
    if (currentDetails.type === 'crypto') return currentDetails.address;
    if (currentDetails.type === 'brl') return currentDetails.pixKey;
    if (currentDetails.type === 'usd') return `${currentDetails.routingNumber}:${currentDetails.accountNumber}`;
    return '';
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    setToastVisible(true);
    setTimeout(() => setToastVisible(false), 2000);
  };

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-end md:items-center justify-center z-50 p-4">
      <Toast message="Copied to clipboard!" visible={toastVisible} />
      <div className="bg-matera-card w-full max-w-md rounded-3xl border border-white/10 overflow-hidden animate-in slide-in-from-bottom-8 md:slide-in-from-bottom-0 md:zoom-in-95 shadow-2xl">
        <div className="p-6">
          <div className="flex justify-between items-center mb-6">
            <h3 className="text-2xl font-bold text-white tracking-tight">Receive {wallet.currency}</h3>
            <button onClick={onClose} className="text-matera-muted hover:text-white p-1 transition-colors"><X size={24} /></button>
          </div>

          {wallet.type === 'crypto' && wallet.networks && (
            <div className="mb-6">
              <select
                value={selectedNetwork}
                onChange={(e) => setSelectedNetwork(e.target.value)}
                className="w-full bg-black/20 border border-white/10 rounded-xl p-3 text-white focus:outline-none focus:border-matera-green font-medium transition-colors"
              >
                {wallet.networks.map(net => <option key={net} value={net}>{net}</option>)}
              </select>
            </div>
          )}

          {requiresTC || !currentDetails ? (
            <div className="space-y-6">
              <div className="p-4 bg-matera-blue/30 rounded-xl border border-matera-blue">
                <p className="text-sm text-matera-muted leading-relaxed">
                  To receive {wallet.currency}{wallet.type === 'crypto' ? ` on ${selectedNetwork}` : ''}, we need to generate a dedicated {wallet.type === 'crypto' ? 'blockchain address' : 'account detail'} linked to your Digital Twin Account.
                  By proceeding, you agree to Matera's Terms & Conditions for receiving funds.
                </p>
              </div>

              <label className="flex items-start gap-3 cursor-pointer group">
                <input
                  type="checkbox"
                  checked={agreed}
                  onChange={(e) => setAgreed(e.target.checked)}
                  className="mt-1 w-4 h-4 rounded border-white/20 bg-matera-bg text-matera-green focus:ring-matera-green focus:ring-offset-matera-card transition-colors"
                />
                <span className="text-sm text-gray-300 group-hover:text-white transition-colors">I agree to the Terms & Conditions</span>
              </label>

              <button
                onClick={handleGenerate}
                disabled={!agreed}
                className="w-full bg-matera-green text-matera-blue-dark font-bold py-4 px-4 rounded-xl disabled:opacity-50 disabled:cursor-not-allowed hover:bg-matera-green-dark transition-colors"
              >
                Generate Details
              </button>
            </div>
          ) : (
            <div className="space-y-5">
              <div className="flex justify-center mb-2">
                <div className="w-56 h-56 bg-white rounded-2xl p-4 flex items-center justify-center">
                  <QRCode value={getQRValue()} size={192} style={{ height: "auto", maxWidth: "100%", width: "100%" }} />
                </div>
              </div>

              <div className="bg-black/20 rounded-xl p-4 border border-white/5">
                {currentDetails.type === 'crypto' && (
                  <>
                    <p className="text-[11px] text-gray-400 mb-1 font-semibold tracking-wider">NETWORK: {currentDetails.network.toUpperCase()}</p>
                    <p className="text-[11px] text-gray-400 mb-1 font-semibold tracking-wider">ADDRESS</p>
                    <div className="flex justify-between items-start gap-3 mt-1">
                      <p className="text-white font-mono text-[13px] break-all font-medium leading-tight">{currentDetails.address}</p>
                      <button onClick={() => handleCopy(currentDetails.type === 'crypto' ? currentDetails.address : '')} className="text-cyan-400 hover:text-cyan-300 shrink-0"><Copy size={20} /></button>
                    </div>
                  </>
                )}
                {currentDetails.type === 'usd' && (
                  <>
                    <div className="mb-3">
                      <p className="text-[11px] text-gray-400 mb-1 font-semibold tracking-wider">ROUTING NUMBER</p>
                      <div className="flex justify-between items-center gap-2 mt-1">
                        <p className="text-white font-mono text-[13px] break-all font-medium leading-tight">{currentDetails.routingNumber}</p>
                        <button onClick={() => handleCopy(currentDetails.type === 'usd' ? currentDetails.routingNumber : '')} className="text-cyan-400 hover:text-cyan-300 shrink-0"><Copy size={20} /></button>
                      </div>
                    </div>
                    <div>
                      <p className="text-[11px] text-gray-400 mb-1 font-semibold tracking-wider">ACCOUNT NUMBER</p>
                      <div className="flex justify-between items-center gap-2 mt-1">
                        <p className="text-white font-mono text-[13px] break-all font-medium leading-tight">{currentDetails.accountNumber}</p>
                        <button onClick={() => handleCopy(currentDetails.type === 'usd' ? currentDetails.accountNumber : '')} className="text-cyan-400 hover:text-cyan-300 shrink-0"><Copy size={20} /></button>
                      </div>
                    </div>
                  </>
                )}
                {currentDetails.type === 'brl' && (
                  <>
                    <p className="text-[11px] text-gray-400 mb-1 font-semibold tracking-wider">PIX KEY</p>
                    <div className="flex justify-between items-center gap-2 mt-1">
                      <p className="text-white font-mono text-[13px] break-all font-medium leading-tight">{currentDetails.pixKey}</p>
                      <button onClick={() => handleCopy(currentDetails.type === 'brl' ? currentDetails.pixKey : '')} className="text-cyan-400 hover:text-cyan-300 shrink-0"><Copy size={20} /></button>
                    </div>
                  </>
                )}
              </div>

              <div className="flex items-start gap-3 text-[13px] text-gray-300 bg-black/20 p-4 rounded-xl border border-white/5">
                <AlertCircle size={18} className="shrink-0 text-cyan-400 mt-0.5" />
                <p className="leading-relaxed">Funds sent to these details will be automatically credited to your Digital Twin Account.</p>
              </div>

              <button
                onClick={onClose}
                className="w-full bg-black/20 text-white font-bold py-3.5 px-4 rounded-xl border border-white/20 hover:bg-white/5 transition-colors mt-2"
              >
                OK
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function BuyModal({ wallet, onClose }: { wallet: WalletType, onClose: () => void }) {
  const { wallets } = useStore();
  const [usdcAmount, setUsdcAmount] = useState('');
  const [fiatAmount, setFiatAmount] = useState('');
  const [fiatCurrency, setFiatCurrency] = useState<'USD' | 'BRL'>('USD');
  const [success, setSuccess] = useState(false);

  const RATE_USD = 1;
  const RATE_BRL = 5;

  const handleUsdcChange = (val: string) => {
    setUsdcAmount(val);
    const num = parseFloat(val);
    if (!isNaN(num)) {
      setFiatAmount((num * (fiatCurrency === 'USD' ? RATE_USD : RATE_BRL)).toString());
    } else {
      setFiatAmount('');
    }
  };

  const handleFiatChange = (val: string) => {
    setFiatAmount(val);
    const num = parseFloat(val);
    if (!isNaN(num)) {
      setUsdcAmount((num / (fiatCurrency === 'USD' ? RATE_USD : RATE_BRL)).toString());
    } else {
      setUsdcAmount('');
    }
  };

  const handleCurrencyChange = (currency: 'USD' | 'BRL') => {
    setFiatCurrency(currency);
    const num = parseFloat(usdcAmount);
    if (!isNaN(num)) {
      setFiatAmount((num * (currency === 'USD' ? RATE_USD : RATE_BRL)).toString());
    }
  };

  const handleBuy = () => {
    setSuccess(true);
    setTimeout(onClose, 2000);
  };

  if (success) {
    return (
      <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
        <div className="bg-matera-card w-full max-w-sm rounded-3xl border border-white/10 p-8 text-center animate-in zoom-in-95">
          <CheckCircle2 size={64} className="text-matera-green mx-auto mb-4" />
          <h3 className="text-2xl font-bold text-white mb-2">Purchase Successful</h3>
          <p className="text-matera-muted">Your {wallet.currency} has been credited to your Digital Twin Account.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-end md:items-center justify-center z-50 p-4">
      <div className="bg-matera-card w-full max-w-md rounded-3xl border border-white/10 overflow-hidden animate-in slide-in-from-bottom-8 md:slide-in-from-bottom-0 md:zoom-in-95">
        <div className="p-6">
          <div className="flex justify-between items-center mb-6">
            <h3 className="text-xl font-bold text-white">Buy {wallet.currency}</h3>
            <button onClick={onClose} className="text-matera-muted hover:text-white p-1"><X size={24} /></button>
          </div>

          <div className="space-y-6">
            <div>
              <label className="block text-sm font-medium text-matera-muted mb-2">I want to buy</label>
              <div className="relative">
                <input
                  type="number"
                  value={usdcAmount}
                  onChange={(e) => handleUsdcChange(e.target.value)}
                  placeholder="0.00"
                  className="w-full bg-matera-bg border border-white/10 rounded-xl py-4 pl-4 pr-16 text-2xl font-semibold text-white focus:outline-none focus:border-matera-green"
                />
                <div className="absolute inset-y-0 right-4 flex items-center pointer-events-none">
                  <span className="text-white font-medium">{wallet.currency}</span>
                </div>
              </div>
            </div>

            <div className="flex justify-center -my-2 relative z-10">
              <div className="bg-matera-blue w-8 h-8 rounded-full flex items-center justify-center border border-white/10 text-matera-muted">
                <ArrowDownLeft size={16} />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-matera-muted mb-2">I will pay</label>
              <div className="flex gap-2">
                <div className="relative flex-1">
                  <input
                    type="number"
                    value={fiatAmount}
                    onChange={(e) => handleFiatChange(e.target.value)}
                    placeholder="0.00"
                    className="w-full h-full bg-matera-bg border border-white/10 rounded-xl py-3 pl-4 pr-4 text-xl font-semibold text-white focus:outline-none focus:border-matera-green"
                  />
                </div>
                <select
                  value={fiatCurrency}
                  onChange={(e) => handleCurrencyChange(e.target.value as 'USD' | 'BRL')}
                  className="w-28 bg-matera-bg border border-white/10 rounded-xl p-3 text-white focus:outline-none focus:border-matera-green font-medium"
                >
                  <option value="USD">USD</option>
                  <option value="BRL">BRL</option>
                </select>
              </div>
              <p className="text-xs text-matera-muted mt-2 text-right">
                Exchange Rate: 1 {wallet.currency} = {fiatCurrency === 'USD' ? RATE_USD : RATE_BRL} {fiatCurrency}
              </p>
            </div>

            <button
              onClick={handleBuy}
              disabled={!usdcAmount || parseFloat(usdcAmount) <= 0}
              className="w-full bg-matera-green text-matera-blue-dark font-semibold py-4 px-4 rounded-xl disabled:opacity-50 disabled:cursor-not-allowed hover:bg-matera-green-dark transition-colors mt-2 text-lg shadow-lg"
            >
              Confirm Purchase
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function SellModal({ wallet, onClose }: { wallet: WalletType, onClose: () => void }) {
  const { wallets } = useStore();
  const [cryptoAmount, setCryptoAmount] = useState('');
  const [fiatAmount, setFiatAmount] = useState('');
  const [fiatCurrency, setFiatCurrency] = useState<'USD' | 'BRL'>('USD');
  const [success, setSuccess] = useState(false);

  const RATE_USD = 1;
  const RATE_BRL = 5;

  const handleCryptoChange = (val: string) => {
    setCryptoAmount(val);
    const num = parseFloat(val);
    if (!isNaN(num)) {
      setFiatAmount((num * (fiatCurrency === 'USD' ? RATE_USD : RATE_BRL)).toString());
    } else {
      setFiatAmount('');
    }
  };

  const handleFiatChange = (val: string) => {
    setFiatAmount(val);
    const num = parseFloat(val);
    if (!isNaN(num)) {
      setCryptoAmount((num / (fiatCurrency === 'USD' ? RATE_USD : RATE_BRL)).toString());
    } else {
      setCryptoAmount('');
    }
  };

  const handleCurrencyChange = (currency: 'USD' | 'BRL') => {
    setFiatCurrency(currency);
    const num = parseFloat(cryptoAmount);
    if (!isNaN(num)) {
      setFiatAmount((num * (currency === 'USD' ? RATE_USD : RATE_BRL)).toString());
    }
  };

  const handleSell = () => {
    setSuccess(true);
    setTimeout(onClose, 2000);
  };

  if (success) {
    return (
      <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
        <div className="bg-matera-card w-full max-w-sm rounded-3xl border border-white/10 p-8 text-center animate-in zoom-in-95">
          <CheckCircle2 size={64} className="text-matera-green mx-auto mb-4" />
          <h3 className="text-2xl font-bold text-white mb-2">Sale Successful</h3>
          <p className="text-matera-muted">Your {wallet.currency} has been sold and your Fiat balance has been credited.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-end md:items-center justify-center z-50 p-4">
      <div className="bg-matera-card w-full max-w-md rounded-3xl border border-white/10 overflow-hidden animate-in slide-in-from-bottom-8 md:slide-in-from-bottom-0 md:zoom-in-95">
        <div className="p-6">
          <div className="flex justify-between items-center mb-6">
            <h3 className="text-xl font-bold text-white">Sell {wallet.currency}</h3>
            <button onClick={onClose} className="text-matera-muted hover:text-white p-1"><X size={24} /></button>
          </div>

          <div className="space-y-6">
            <div>
              <label className="block text-sm font-medium text-matera-muted mb-2">I want to sell</label>
              <div className="relative">
                <input
                  type="number"
                  value={cryptoAmount}
                  onChange={(e) => handleCryptoChange(e.target.value)}
                  placeholder="0.00"
                  className="w-full bg-matera-bg border border-white/10 rounded-xl py-4 pl-4 pr-16 text-2xl font-semibold text-white focus:outline-none focus:border-matera-green"
                />
                <div className="absolute inset-y-0 right-4 flex items-center pointer-events-none">
                  <span className="text-white font-medium">{wallet.currency}</span>
                </div>
              </div>
            </div>

            <div className="flex justify-center -my-2 relative z-10">
              <div className="bg-matera-blue w-8 h-8 rounded-full flex items-center justify-center border border-white/10 text-matera-muted">
                <ArrowDownLeft size={16} />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-matera-muted mb-2">I will receive</label>
              <div className="flex gap-2">
                <div className="relative flex-1">
                  <input
                    type="number"
                    value={fiatAmount}
                    onChange={(e) => handleFiatChange(e.target.value)}
                    placeholder="0.00"
                    className="w-full h-full bg-matera-bg border border-white/10 rounded-xl py-3 pl-4 pr-4 text-xl font-semibold text-white focus:outline-none focus:border-matera-green"
                  />
                </div>
                <select
                  value={fiatCurrency}
                  onChange={(e) => handleCurrencyChange(e.target.value as 'USD' | 'BRL')}
                  className="w-28 bg-matera-bg border border-white/10 rounded-xl p-3 text-white focus:outline-none focus:border-matera-green font-medium"
                >
                  <option value="USD">USD</option>
                  <option value="BRL">BRL</option>
                </select>
              </div>
              <p className="text-xs text-matera-muted mt-2 text-right">
                Exchange Rate: 1 {wallet.currency} = {fiatCurrency === 'USD' ? RATE_USD : RATE_BRL} {fiatCurrency}
              </p>
            </div>

            <button
              onClick={handleSell}
              disabled={!cryptoAmount || parseFloat(cryptoAmount) <= 0}
              className="w-full bg-matera-green text-matera-blue-dark font-semibold py-4 px-4 rounded-xl disabled:opacity-50 disabled:cursor-not-allowed hover:bg-matera-green-dark transition-colors mt-2 text-lg shadow-lg"
            >
              Confirm Sale
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function SendModal({ wallet, onClose }: { wallet: WalletType, onClose: () => void }) {
  const { sendFunds } = useStore();
  const [amount, setAmount] = useState('');
  const [destination, setDestination] = useState('');
  const [network, setNetwork] = useState(wallet.networks?.[0] || '');
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState('');

  const handleSend = () => {
    try {
      setError('');
      const numAmount = parseFloat(amount);
      if (isNaN(numAmount) || numAmount <= 0) throw new Error('Invalid amount');
      if (!destination) throw new Error('Destination is required');

      sendFunds(wallet.id, numAmount, destination, network);
      setSuccess(true);
      setTimeout(onClose, 2000);
    } catch (err: any) {
      setError(err.message);
    }
  };

  if (success) {
    return (
      <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
        <div className="bg-matera-card w-full max-w-sm rounded-3xl border border-white/10 p-8 text-center animate-in zoom-in-95">
          <CheckCircle2 size={64} className="text-matera-green mx-auto mb-4" />
          <h3 className="text-2xl font-bold text-white mb-2">Sent Successfully</h3>
          <p className="text-matera-muted">Your transaction has been recorded on the Digital Twin Account.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-end md:items-center justify-center z-50 p-4">
      <div className="bg-matera-card w-full max-w-md rounded-3xl border border-white/10 overflow-hidden animate-in slide-in-from-bottom-8 md:slide-in-from-bottom-0 md:zoom-in-95">
        <div className="p-6">
          <div className="flex justify-between items-center mb-6">
            <h3 className="text-xl font-bold text-white">Send {wallet.currency}</h3>
            <button onClick={onClose} className="text-matera-muted hover:text-white p-1"><X size={24} /></button>
          </div>

          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-matera-muted mb-2">Amount</label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <span className="text-matera-muted">{wallet.type === 'fiat' ? wallet.symbol : ''}</span>
                </div>
                <input
                  type="number"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  placeholder="0.00"
                  className="w-full bg-matera-bg border border-white/10 rounded-xl py-3 pl-8 pr-12 text-white focus:outline-none focus:border-matera-green"
                />
                <div className="absolute inset-y-0 right-0 pr-3 flex items-center pointer-events-none">
                  <span className="text-matera-muted">{wallet.type === 'crypto' ? wallet.symbol : ''}</span>
                </div>
              </div>
              <p className="text-xs text-matera-muted mt-1 text-right">Available: {wallet.balance}</p>
            </div>

            {wallet.type === 'crypto' && wallet.networks && (
              <div>
                <label className="block text-sm font-medium text-matera-muted mb-2">Network</label>
                <select
                  value={network}
                  onChange={(e) => setNetwork(e.target.value)}
                  className="w-full bg-matera-bg border border-white/10 rounded-xl p-3 text-white focus:outline-none focus:border-matera-green"
                >
                  {wallet.networks.map(net => <option key={net} value={net}>{net}</option>)}
                </select>
              </div>
            )}

            <div>
              <label className="block text-sm font-medium text-matera-muted mb-2">
                {wallet.currency === 'BRL' ? 'Pix Alias (CPF/CNPJ, Email, Phone)' :
                  wallet.currency === 'USD' ? 'Routing & Account Number' :
                    'Destination Address'}
              </label>
              <input
                type="text"
                value={destination}
                onChange={(e) => setDestination(e.target.value)}
                placeholder={
                  wallet.currency === 'BRL' ? 'Enter Pix key' :
                    wallet.currency === 'USD' ? 'e.g. 021000021 123456789' :
                      '0x...'
                }
                className="w-full bg-matera-bg border border-white/10 rounded-xl p-3 text-white focus:outline-none focus:border-matera-green"
              />
            </div>

            {error && <p className="text-red-400 text-sm">{error}</p>}

            <button
              onClick={handleSend}
              className="w-full bg-matera-green text-matera-blue-dark font-semibold py-3 px-4 rounded-xl hover:bg-matera-green-dark transition-colors mt-4"
            >
              Confirm Send
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function QRScannerModal({ onClose }: { onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 bg-black flex flex-col">
      {/* Header */}
      <div className="flex items-center px-6 pt-14 pb-4">
        <button onClick={onClose} className="text-white/60 hover:text-white transition-colors p-1">
          <X size={26} />
        </button>
        <h2 className="flex-1 text-center text-white font-semibold text-lg pr-8">
          Scan a Payment QR Code
        </h2>
      </div>
      <p className="text-white/40 text-sm text-center px-10 mb-10">
        Align any QR code within the frame — we'll detect the type and take you straight to payment
      </p>

      {/* Viewfinder */}
      <div className="flex-1 flex items-center justify-center px-10">
        <div className="relative w-full max-w-[280px] aspect-square">
          {/* Background of the viewfinder area */}
          <div className="absolute inset-0 bg-white/[0.04] rounded-xl" />

          {/* Corner brackets */}
          <div className="absolute top-0 left-0 w-9 h-9 border-t-[3px] border-l-[3px] border-matera-green rounded-tl-xl" />
          <div className="absolute top-0 right-0 w-9 h-9 border-t-[3px] border-r-[3px] border-matera-green rounded-tr-xl" />
          <div className="absolute bottom-0 left-0 w-9 h-9 border-b-[3px] border-l-[3px] border-matera-green rounded-bl-xl" />
          <div className="absolute bottom-0 right-0 w-9 h-9 border-b-[3px] border-r-[3px] border-matera-green rounded-br-xl" />

          {/* Animated scan line */}
          <div
            className="absolute left-3 right-3 h-[2px] rounded-full"
            style={{
              background: 'linear-gradient(90deg, transparent, #00E5FF, transparent)',
              boxShadow: '0 0 8px 2px rgba(0,229,255,0.4)',
              animation: 'qr-scan 2.4s ease-in-out infinite',
            }}
          />

          {/* Placeholder QR icon to hint what to put here */}
          <div className="absolute inset-0 flex items-center justify-center">
            <QrCode size={72} className="text-white/10" />
          </div>
        </div>
      </div>

      {/* Bottom hint */}
      <div className="pb-16 text-center">
        <p className="text-white/30 text-xs">Keep the QR code steady inside the frame</p>
      </div>
    </div>
  );
}

function BottomNav({ onScanPress }: { onScanPress: () => void }) {
  return (
    <div className="fixed bottom-0 left-0 right-0 bg-matera-card border-t border-white/5 md:hidden">
      <div className="flex justify-around items-end px-4 pt-2 pb-4">
        <button className="flex flex-col items-center gap-1 text-matera-green pb-1">
          <Wallet size={24} />
          <span className="text-[10px] font-medium">Wallets</span>
        </button>

        {/* Central raised QR scan button */}
        <button
          onClick={onScanPress}
          className="flex flex-col items-center gap-1 -mt-7"
        >
          <div className="w-16 h-16 rounded-full bg-matera-green flex items-center justify-center shadow-[0_0_20px_rgba(0,229,255,0.4)] active:scale-95 transition-transform">
            <QrCode size={28} className="text-matera-blue-dark" />
          </div>
          <span className="text-[10px] font-medium text-matera-muted mt-0.5">Scan</span>
        </button>

        <button className="flex flex-col items-center gap-1 text-matera-muted hover:text-white transition-colors pb-1">
          <Settings size={24} />
          <span className="text-[10px] font-medium">Settings</span>
        </button>
      </div>
    </div>
  );
}

function Sidebar({ user, onLogout }: { user: User; onLogout: () => void }) {
  return (
    <div className="hidden md:flex flex-col w-64 bg-matera-card border-r border-white/5 h-screen fixed left-0 top-0 p-6">
      <div className="mb-10">
        <img
          src="https://d2lq74zxbg4jiz.cloudfront.net/matera_logo_8e3c7e36a1.svg"
          alt="Matera"
          className="h-6"
          referrerPolicy="no-referrer"
        />
      </div>
      <nav className="space-y-2 flex-1">
        <button className="w-full flex items-center gap-3 bg-matera-blue/20 text-matera-green px-4 py-3 rounded-xl font-medium">
          <Wallet size={20} /> Wallets
        </button>
        <button className="w-full flex items-center gap-3 text-matera-muted hover:text-white hover:bg-white/5 px-4 py-3 rounded-xl font-medium transition-colors">
          <Activity size={20} /> Activity
        </button>
        <button className="w-full flex items-center gap-3 text-matera-muted hover:text-white hover:bg-white/5 px-4 py-3 rounded-xl font-medium transition-colors">
          <Settings size={20} /> Settings
        </button>
      </nav>
      <div className="border-t border-white/5 pt-4">
        <div className="flex items-center gap-3 mb-3">
          {user.picture && (
            <img src={user.picture} alt={user.name} className="w-8 h-8 rounded-full" referrerPolicy="no-referrer" />
          )}
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium text-white truncate">{user.name}</p>
            <p className="text-xs text-matera-muted truncate">{user.email}</p>
          </div>
        </div>
        <button
          onClick={onLogout}
          className="w-full text-left text-sm text-matera-muted hover:text-white transition-colors px-2 py-1"
        >
          Sign out
        </button>
      </div>
    </div>
  );
}

interface User {
  email: string;
  name: string;
  picture: string;
}

function Login({ onLogin }: { onLogin: (user: User) => void }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const login = useGoogleLogin({
    scope: 'openid email profile',
    onSuccess: async (tokenResponse) => {
      setLoading(true);
      setError('');
      try {
        const res = await fetch(`${import.meta.env.BASE_URL}api/auth/google`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
          body: JSON.stringify({ access_token: tokenResponse.access_token }),
        });
        let data: any = {};
        try { data = await res.json(); } catch { /* ignore non-JSON body */ }
        if (!res.ok) {
          setError(data.error ?? `Server error (${res.status})`);
          return;
        }
        onLogin(data.user);
      } catch {
        setError('Could not reach the server. Is it running?');
      } finally {
        setLoading(false);
      }
    },
    onError: () => {
      setError('Google sign-in was cancelled or failed.');
      setLoading(false);
    },
  });

  return (
    <div className="min-h-screen bg-matera-bg flex flex-col items-center justify-center p-4">
      <div className="w-full max-w-md bg-matera-card rounded-3xl p-8 border border-white/5 shadow-2xl flex flex-col items-center relative overflow-hidden animate-in zoom-in-95 duration-500">
        <div className="absolute top-0 right-0 w-64 h-64 bg-matera-green/10 rounded-full blur-3xl -mr-32 -mt-32"></div>
        <div className="absolute bottom-0 left-0 w-64 h-64 bg-matera-blue/20 rounded-full blur-3xl -ml-32 -mb-32"></div>

        <img
          src="https://d2lq74zxbg4jiz.cloudfront.net/matera_logo_8e3c7e36a1.svg"
          alt="Matera"
          className="h-8 mb-12 relative z-10"
        />

        <div className="text-center mb-10 relative z-10">
          <h1 className="text-3xl font-bold text-white mb-3">Welcome Back</h1>
          <p className="text-matera-muted">Sign in to access your universal digital wallet and off-chain ledger.</p>
        </div>

        <button
          onClick={() => { setError(''); login(); }}
          disabled={loading}
          className="relative z-10 w-full bg-white text-gray-900 font-semibold py-4 px-6 rounded-xl flex items-center justify-center gap-3 hover:bg-gray-100 transition-colors shadow-lg active:scale-[0.98] group disabled:opacity-60 disabled:cursor-not-allowed"
        >
          <div className="bg-white p-1 rounded-full group-hover:scale-110 transition-transform">
            {loading ? (
              <svg className="w-5 h-5 animate-spin text-gray-400" viewBox="0 0 24 24" fill="none">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
              </svg>
            ) : (
              <svg className="w-5 h-5" viewBox="0 0 24 24">
                <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
                <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
                <path fill="#FBBC05" d="M5.84 14.1c-.22-.66-.35-1.36-.35-2.1s.13-1.44.35-2.1V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l3.66-2.84z" />
                <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z" />
              </svg>
            )}
          </div>
          {loading ? 'Signing in…' : 'Continue with Google'}
        </button>

        {error && (
          <p className="mt-4 text-sm text-red-400 text-center relative z-10">{error}</p>
        )}

        <p className="mt-8 text-xs text-matera-muted text-center relative z-10 w-4/5 mx-auto opacity-70">
          By continuing, you agree to Matera's Terms of Service and Privacy Policy.
        </p>
      </div>
    </div>
  );
}

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [authChecked, setAuthChecked] = useState(false);
  const [showScanner, setShowScanner] = useState(false);

  useEffect(() => {
    fetch(`${import.meta.env.BASE_URL}api/auth/me`, { credentials: 'include' })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (data?.user) setUser(data.user);
      })
      .finally(() => setAuthChecked(true));
  }, []);

  const handleLogout = useCallback(async () => {
    await fetch(`${import.meta.env.BASE_URL}api/auth/logout`, { method: 'POST', credentials: 'include' });
    setUser(null);
  }, []);

  if (!authChecked) return null;

  if (!user) {
    return <Login onLogin={setUser} />;
  }

  return (
    <StoreProvider>
      <div className="min-h-screen bg-matera-bg pb-20 md:pb-0 md:pl-64 animate-in fade-in duration-500">
        <Sidebar user={user} onLogout={handleLogout} />
        <Dashboard />
        <BottomNav onScanPress={() => setShowScanner(true)} />
        {showScanner && <QRScannerModal onClose={() => setShowScanner(false)} />}
      </div>
    </StoreProvider>
  );
}

