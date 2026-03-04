package com.matera.digitaltwin.backoffice.adaptor.solana;

import com.matera.digitaltwin.backoffice.dto.response.BalanceResponse;
import com.matera.digitaltwin.backoffice.dto.response.SendResultResponse;
import com.matera.digitaltwin.backoffice.dto.response.TransactionResponse;
import com.matera.digitaltwin.backoffice.dto.response.WalletInfoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.solana.programs.clients.NativeProgramClient;
import software.sava.solana.programs.system.SystemProgram;
import software.sava.solana.programs.token.TokenProgram;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import software.sava.rpc.json.http.response.TokenBalance;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Solana blockchain adaptor backed by the official Sava SDK.
 * https://sava.software | https://github.com/sava-software/sava
 *
 * <p>Key derivation: SLIP-0010 ed25519 at path m/44'/501'/{accountIndex}'/0'.
 * SOL amounts are always decimal (1 SOL = 10^9 lamports).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SolanaAdaptorImpl implements SolanaAdaptor {

    private static final long LAMPORTS_PER_SOL = 1_000_000_000L;
    /** SLIP-0010 curve constant for ed25519. */
    private static final byte[] ED25519_CURVE_SEED =
            "ed25519 seed".getBytes(StandardCharsets.UTF_8);

    private final SolanaRpcClient rpcClient;
    private final SolanaAccounts solanaAccounts;
    private final NativeProgramClient nativeProgramClient;

    @Value("${solana.explorer-base-url}")
    private String explorerBaseUrl;

    @Value("${solana.rpc-url}")
    private String rpcUrl;

    /** Delay between sequential getTransaction calls (ms). 0 = disabled (use with Helius/QuickNode). */
    @Value("${solana.tx-detail-delay-ms:200}")
    private long txDetailDelayMs;

    // ──────────────────────────── BlockchainAdaptor ────────────────────────────

    @Override
    public String getNetwork() {
        return "solana";
    }

    @Override
    public WalletInfoResponse generateWallet() {
        // generatePrivateKeyPairBytes() → byte[64]: privKey[0..31] || pubKey[32..63]
        byte[] keyPairBytes = Signer.generatePrivateKeyPairBytes();
        Signer signer = Signer.createFromKeyPair(keyPairBytes);
        String address = signer.publicKey().toBase58();
        // Expose only the 32-byte private key portion (base58) — caller must store securely
        String privateKey = Base58.encode(Arrays.copyOfRange(keyPairBytes, 0, 32));

        return WalletInfoResponse.builder()
                .network("solana")
                .address(address)
                .publicKey(address)
                .privateKey(privateKey)
                .build();
    }

    @Override
    public BalanceResponse getNativeBalance(String address) {
        try {
            var pubKey = PublicKey.fromBase58Encoded(address);
            var lamportsResult = rpcClient.getBalance(pubKey).join();

            return BalanceResponse.builder()
                    .address(address)
                    .balance(lamportsResult.lamports())
                    .currency("SOL")
                    .network("solana")
                    .build();
        } catch (Exception e) {
            log.error("getBalance failed for {}: {}", address, e.getMessage());
            throw new RuntimeException("Failed to fetch SOL balance: " + e.getMessage(), e);
        }
    }

    @Override
    public BalanceResponse getTokenBalance(String address, String tokenMintOrContract) {
        try {
            var owner = PublicKey.fromBase58Encoded(address);
            var mint = PublicKey.fromBase58Encoded(tokenMintOrContract);
            var ata = findAssociatedTokenAddress(owner, mint);

            var tokenAmount = rpcClient.getTokenAccountBalance(ata).join();

            return BalanceResponse.builder()
                    .address(address)
                    .balance(tokenAmount.amount().longValue())
                    .currency(resolveCurrencyLabel(tokenMintOrContract))
                    .network("solana")
                    .build();
        } catch (Exception e) {
            log.error("getTokenBalance failed for {} mint {}: {}", address, tokenMintOrContract, e.getMessage());
            throw new RuntimeException("Failed to fetch token balance: " + e.getMessage(), e);
        }
    }

    @Override
    public SendResultResponse sendNative(String fromPrivateKey, String toAddress, long amount) {
        try {
            Signer signer = signerFromBase58PrivKey(fromPrivateKey);
            var toPubKey = PublicKey.fromBase58Encoded(toAddress);

            var blockHash = rpcClient.getLatestBlockHash().join();
            var feePayer = AccountMeta.createFeePayer(signer.publicKey());

            Instruction ix = SystemProgram.transfer(
                    solanaAccounts.invokedSystemProgram(),
                    signer.publicKey(),
                    toPubKey,
                    amount);

            Transaction tx = Transaction.createTx(feePayer, List.of(ix));
            String signature = rpcClient.sendTransaction(tx, signer, Base58.decode(blockHash.blockHash())).join();

            log.info("sendNative: {} lamports {} → {} sig={}", amount,
                    signer.publicKey().toBase58(), toAddress, signature);

            return SendResultResponse.builder()
                    .txHash(signature)
                    .fromAddress(signer.publicKey().toBase58())
                    .toAddress(toAddress)
                    .amount(amount)
                    .currency("SOL")
                    .fee(estimateFee(signer.publicKey().toBase58(), toAddress))
                    .status("submitted")
                    .explorerUrl(getExplorerUrl(signature))
                    .build();
        } catch (Exception e) {
            log.error("sendNative failed: {}", e.getMessage());
            throw new RuntimeException("SOL transfer failed: " + e.getMessage(), e);
        }
    }

    @Override
    public SendResultResponse sendToken(String fromPrivateKey, String toAddress,
                                        String tokenMint, long amount) {
        /*
         * SPL token transfer flow:
         *   1. Resolve sender's and recipient's Associated Token Accounts (ATAs)
         *   2. Build TokenProgram.transfer instruction
         *   3. Fetch blockhash, build + sign + send transaction
         *
         * Amount is raw units (e.g. 1_000_000 = 1 USDC, since USDC has 6 decimals).
         * The recipient's ATA must already exist. Creating ATAs requires an extra
         * createATA instruction via nativeProgramClient (not implemented here).
         */
        try {
            Signer signer = signerFromBase58PrivKey(fromPrivateKey);
            var mintPubKey = PublicKey.fromBase58Encoded(tokenMint);
            var recipientPubKey = PublicKey.fromBase58Encoded(toAddress);

            var senderAta = findAssociatedTokenAddress(signer.publicKey(), mintPubKey);
            var recipientAta = findAssociatedTokenAddress(recipientPubKey, mintPubKey);

            var blockHash = rpcClient.getLatestBlockHash().join();
            var feePayer = AccountMeta.createFeePayer(signer.publicKey());

            Instruction ix = TokenProgram.transfer(
                    solanaAccounts.invokedTokenProgram(),
                    senderAta,
                    recipientAta,
                    amount,
                    signer.publicKey());

            Transaction tx = Transaction.createTx(feePayer, List.of(ix));
            String signature = rpcClient.sendTransaction(tx, signer, Base58.decode(blockHash.blockHash())).join();

            log.info("sendToken: {} raw units {} {} → {} sig={}", amount,
                    resolveCurrencyLabel(tokenMint),
                    signer.publicKey().toBase58(), toAddress, signature);

            return SendResultResponse.builder()
                    .txHash(signature)
                    .fromAddress(signer.publicKey().toBase58())
                    .toAddress(toAddress)
                    .amount(amount)
                    .currency(resolveCurrencyLabel(tokenMint))
                    .fee(estimateFee(signer.publicKey().toBase58(), toAddress))
                    .status("submitted")
                    .explorerUrl(getExplorerUrl(signature))
                    .build();
        } catch (Exception e) {
            log.error("sendToken failed: {}", e.getMessage());
            throw new RuntimeException("SPL token transfer failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TransactionResponse> getTransactionHistory(String address, int limit) {
        try {
            var pubKey = PublicKey.fromBase58Encoded(address);
            var sigs = rpcClient.getSignaturesForAddress(pubKey, limit).join();

            return sigs.stream().map(sig -> TransactionResponse.builder()
                    .txHash(sig.signature())
                    .status(sig.transactionError() == null ? "confirmed" : "failed")
                    .slot(sig.slot())
                    .timestamp(sig.blockTime().isPresent()
                            ? Instant.ofEpochSecond(sig.blockTime().getAsLong()) : null)
                    .fromAddress(address)
                    .currency("SOL")
                    .explorerUrl(getExplorerUrl(sig.signature()))
                    .build()
            ).toList();
        } catch (Exception e) {
            log.error("getTransactionHistory failed for {}: {}", address, e.getMessage());
            throw new RuntimeException("Failed to fetch transaction history: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TransactionResponse> getTokenTransactionHistory(String address, String tokenMint, int limit) {
        try {
            var ownerPubKey = PublicKey.fromBase58Encoded(address);
            var mintPubKey  = PublicKey.fromBase58Encoded(tokenMint);
            var ata         = findAssociatedTokenAddress(ownerPubKey, mintPubKey);

            var sigs    = rpcClient.getSignaturesForAddress(ata, limit).join();
            var currency = resolveCurrencyLabel(tokenMint);
            var results  = new ArrayList<TransactionResponse>(sigs.size());

            // Sequential calls — public RPC rate-limits concurrent getTransaction requests.
            // Set solana.tx-detail-delay-ms=0 when using Helius or QuickNode.
            for (var sig : sigs) {
                var tx = rpcClient.getTransaction(sig.signature()).join();
                if (txDetailDelayMs > 0) {
                    try { Thread.sleep(txDetailDelayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }

                String fromAddr = address;
                String toAddr  = null;
                Long amount    = null;

                if (tx != null && tx.meta() != null) {
                    var pre  = tx.meta().preTokenBalances();
                    var post = tx.meta().postTokenBalances();
                    if (pre != null && post != null) {
                        for (TokenBalance postBal : post) {
                            if (!mintPubKey.equals(postBal.mint())) continue;
                            BigInteger preAmt = pre.stream()
                                    .filter(p -> p.accountIndex() == postBal.accountIndex())
                                    .map(TokenBalance::amount)
                                    .findFirst().orElse(BigInteger.ZERO);
                            BigInteger diff = postBal.amount().subtract(preAmt);
                            if (diff.signum() > 0) {
                                toAddr = postBal.owner().toBase58();
                                amount = diff.longValue();
                            } else if (diff.signum() < 0) {
                                fromAddr = postBal.owner().toBase58();
                            }
                        }
                    }
                }

                results.add(TransactionResponse.builder()
                        .txHash(sig.signature())
                        .status(sig.transactionError() == null ? "confirmed" : "failed")
                        .slot(sig.slot())
                        .timestamp(sig.blockTime().isPresent()
                                ? Instant.ofEpochSecond(sig.blockTime().getAsLong()) : null)
                        .fromAddress(fromAddr)
                        .toAddress(toAddr)
                        .amount(amount)
                        .currency(currency)
                        .explorerUrl(getExplorerUrl(sig.signature()))
                        .build());
            }
            return results;
        } catch (Exception e) {
            log.error("getTokenTransactionHistory failed for {} mint {}: {}", address, tokenMint, e.getMessage());
            throw new RuntimeException("Failed to fetch token transaction history: " + e.getMessage(), e);
        }
    }

    @Override
    public String getExplorerUrl(String txHash) {
        String cluster = rpcUrl.contains("devnet") ? "?cluster=devnet"
                : rpcUrl.contains("testnet") ? "?cluster=testnet" : "";
        return explorerBaseUrl + "/tx/" + txHash + cluster;
    }

    @Override
    public boolean isConfirmed(String txHash) {
        try {
            var tx = rpcClient.getTransaction(txHash).join();
            return tx != null;
        } catch (Exception e) {
            log.warn("isConfirmed check failed for {}: {}", txHash, e.getMessage());
            return false;
        }
    }

    // ──────────────────────────── SolanaAdaptor ────────────────────────────────

    @Override
    public WalletInfoResponse deriveAddress(String mnemonic, int accountIndex) {
        try {
            List<String> words = Arrays.asList(mnemonic.trim().split("\\s+"));
            MnemonicCode.INSTANCE.check(words);   // validates word list + checksum → throws MnemonicException if invalid
            byte[] seed = MnemonicCode.INSTANCE.toSeed(words, "");

            // SLIP-0010 hardened derivation: m/44'/501'/{accountIndex}'/0'
            int[] path = {0x8000002C, 0x800001F5, 0x80000000 + accountIndex, 0x80000000};
            byte[] privKeyBytes = slip10Derive(seed, path);

            // Signer.createFromPrivateKey validates the scalar and derives public key
            Signer signer = Signer.createFromPrivateKey(privKeyBytes);
            String address = signer.publicKey().toBase58();
            String privateKey = Base58.encode(privKeyBytes);

            return WalletInfoResponse.builder()
                    .network(rpcUrl.contains("devnet") ? "solana-devnet" : "solana")
                    .address(address)
                    .publicKey(address)
                    .privateKey(privateKey)
                    .build();
        } catch (MnemonicException e) {
            throw new IllegalArgumentException("Invalid mnemonic: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("BIP44 derivation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long estimateFee(String fromAddress, String toAddress) {
        // Solana's base fee is 5000 lamports per signature for a simple transfer.
        // For production, call rpcClient.getFeeForMessage(serializedMessage).join().
        return 5_000L;
    }

    // ──────────────────────────── private helpers ───────────────────────────────

    /**
     * SLIP-0010 hardened key derivation for ed25519.
     * Returns the 32-byte private key scalar (first half of each 64-byte HMAC output).
     */
    private byte[] slip10Derive(byte[] seed, int[] path) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");

            // Master key from seed
            mac.init(new SecretKeySpec(ED25519_CURVE_SEED, "HmacSHA512"));
            byte[] I = mac.doFinal(seed);

            for (int index : path) {
                byte[] Il = Arrays.copyOfRange(I, 0, 32);
                byte[] Ir = Arrays.copyOfRange(I, 32, 64);
                byte[] data = new byte[37];
                data[0] = 0x00;
                System.arraycopy(Il, 0, data, 1, 32);
                ByteBuffer.wrap(data, 33, 4).putInt(index);
                mac.init(new SecretKeySpec(Ir, "HmacSHA512"));
                I = mac.doFinal(data);
            }

            return Arrays.copyOfRange(I, 0, 32);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("SLIP-0010 derivation error", e);
        }
    }

    /** Reconstruct a {@link Signer} from a base58-encoded 32-byte private key. */
    private Signer signerFromBase58PrivKey(String base58PrivKey) {
        byte[] privKeyBytes = Base58.decode(base58PrivKey);
        return Signer.createFromPrivateKey(privKeyBytes);
    }

    /**
     * Derive the Associated Token Account (ATA) address.
     * Seeds: [owner, tokenProgram, mint] under the ATA program.
     */
    private PublicKey findAssociatedTokenAddress(PublicKey owner, PublicKey mint) {
        try {
            var tokenProgram = solanaAccounts.tokenProgram();
            var ataProgramId = solanaAccounts.associatedTokenAccountProgram();

            List<byte[]> seeds = List.of(
                    owner.toByteArray(),
                    tokenProgram.toByteArray(),
                    mint.toByteArray());

            return PublicKey.findProgramAddress(seeds, ataProgramId).publicKey();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive ATA: " + e.getMessage(), e);
        }
    }

    private String resolveCurrencyLabel(String mint) {
        return switch (mint) {
            case "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" -> "USDC";
            case "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" -> "USDT";
            default -> mint.substring(0, Math.min(8, mint.length())) + "…";
        };
    }
}
