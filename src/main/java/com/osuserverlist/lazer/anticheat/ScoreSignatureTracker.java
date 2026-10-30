package com.osuserverlist.lazer.anticheat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Waits for the anti-cheat server to confirm each submitted score with an Ed25519 signature.
 * Scores are saved immediately; this class only decides whether a flag has to be raised:
 * <ul>
 *   <li>no signature arrives within the timeout: flag {@code no_signature}</li>
 *   <li>a signature arrives but does not match the saved score: flag {@code bad_signature}</li>
 * </ul>
 * The signature may arrive before the score is saved (both servers receive the score at the same time),
 * so early signatures are kept for a while and matched when the score shows up.
 * Disabled (nothing is tracked, nothing is flagged) while no public key is configured.
 */
public class ScoreSignatureTracker {
    private static final Logger logger = LoggerFactory.getLogger(ScoreSignatureTracker.class);

    // DER prefix of an Ed25519 SubjectPublicKeyInfo; the raw 32-byte key follows.
    private static final byte[] ED25519_SPKI_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };
    private static final long EARLY_SIGNATURE_TTL_MS = 10 * 60_000L;
    private static final int MAX_EARLY_SIGNATURES = 10_000;

    public interface FlagSink {
        void flag(int userId, String userName, String code, String summary, String hint);
    }

    public enum SignatureResult { VERIFIED, STORED, REJECTED, DISABLED }

    private record Pending(int userId, String userName, int beatmapId, long totalScore, int maxCombo, long scoreId) {}

    private record EarlySignature(byte[] signature, long receivedAtMs) {}

    private final PublicKey publicKey;
    private final long timeoutMs;
    private final FlagSink sink;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "anticheat-signature-timeout");
        t.setDaemon(true);
        return t;
    });

    private final Map<Long, Pending> pending = new HashMap<>();
    private final Map<Long, EarlySignature> early = new HashMap<>();

    public ScoreSignatureTracker(String publicKeyBase64, long timeoutMs, FlagSink sink) {
        this.timeoutMs = timeoutMs;
        this.sink = sink;
        this.publicKey = parseKey(publicKeyBase64);

        if (publicKey == null) {
            logger.warn("AC_SIGNING_PUBLIC_KEY is not set: score signature checks are disabled");
        } else {
            logger.info("Score signature checks enabled (timeout {} s)", timeoutMs / 1000);
        }
    }

    public boolean isEnabled() {
        return publicKey != null;
    }

    /** The exact bytes the anti-cheat server signs. */
    public static String canonicalMessage(long tokenId, int userId, int beatmapId, long totalScore, int maxCombo) {
        return "owsig1|" + tokenId + "|" + userId + "|" + beatmapId + "|" + totalScore + "|" + maxCombo;
    }

    /** Called right after a score was saved. */
    public void scoreSaved(long tokenId, int userId, String userName, int beatmapId, long totalScore, int maxCombo, long scoreId) {
        if (!isEnabled()) return;

        Pending p = new Pending(userId, userName, beatmapId, totalScore, maxCombo, scoreId);
        EarlySignature signature;

        synchronized (this) {
            signature = early.remove(tokenId);
            if (signature == null) {
                pending.put(tokenId, p);
            }
        }

        if (signature != null) {
            check(tokenId, p, signature.signature());
            return;
        }

        scheduler.schedule(() -> expire(tokenId), timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Called when the anti-cheat server delivers a signature for a score token. */
    public SignatureResult signatureReceived(long tokenId, String signatureBase64) {
        if (!isEnabled()) return SignatureResult.DISABLED;

        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureBase64.trim());
        } catch (IllegalArgumentException e) {
            return SignatureResult.REJECTED;
        }

        Pending p;
        synchronized (this) {
            p = pending.remove(tokenId);
            if (p == null) {
                long now = System.currentTimeMillis();
                early.values().removeIf(e -> now - e.receivedAtMs() > EARLY_SIGNATURE_TTL_MS);
                if (early.size() < MAX_EARLY_SIGNATURES) {
                    early.put(tokenId, new EarlySignature(signature, now));
                }
                return SignatureResult.STORED;
            }
        }

        return check(tokenId, p, signature) ? SignatureResult.VERIFIED : SignatureResult.REJECTED;
    }

    private boolean check(long tokenId, Pending p, byte[] signature) {
        String message = canonicalMessage(tokenId, p.userId(), p.beatmapId(), p.totalScore(), p.maxCombo());

        if (verify(message, signature)) {
            logger.info("Score {} (token {}) confirmed by the anti-cheat server", p.scoreId(), tokenId);
            return true;
        }

        logger.warn("Score {} (token {}) has an invalid anti-cheat signature", p.scoreId(), tokenId);
        sink.flag(p.userId(), p.userName(), "bad_signature",
                "Score signature does not match the submitted score",
                "Score modified after anti-cheat signing, or a forged signature");
        return false;
    }

    private void expire(long tokenId) {
        Pending p;
        synchronized (this) {
            p = pending.remove(tokenId);
        }
        if (p == null) return;

        logger.warn("Score {} (token {}) was not confirmed by the anti-cheat server in time", p.scoreId(), tokenId);
        sink.flag(p.userId(), p.userName(), "no_signature",
                "Score was not confirmed by the anti-cheat server",
                "Client without anti-cheat, modified or outdated client, or the anti-cheat server is blocked");
    }

    private boolean verify(String message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    static PublicKey parseKey(String base64) {
        if (base64 == null || base64.isBlank()) return null;

        try {
            byte[] raw = Base64.getDecoder().decode(base64.trim());
            byte[] der;

            if (raw.length == 32) {
                der = new byte[ED25519_SPKI_PREFIX.length + 32];
                System.arraycopy(ED25519_SPKI_PREFIX, 0, der, 0, ED25519_SPKI_PREFIX.length);
                System.arraycopy(raw, 0, der, ED25519_SPKI_PREFIX.length, 32);
            } else {
                der = raw; // already an X.509 SubjectPublicKeyInfo
            }

            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            logger.error("AC_SIGNING_PUBLIC_KEY is invalid ({}): score signature checks stay disabled", e.getMessage());
            return null;
        }
    }
}
