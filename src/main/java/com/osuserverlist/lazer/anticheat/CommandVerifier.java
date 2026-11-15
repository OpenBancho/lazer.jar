package com.osuserverlist.lazer.anticheat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Checks the signature of a command from the OpenWatcher anti-cheat server (restrict, unrestrict, flag, unflag).
 *
 * The anti-cheat is the only authority for these: this server executes a command only when it carries a valid Ed25519 signature
 * made with the anti-cheat's key (the same AC_SIGNING_PUBLIC_KEY that verifies login tickets and score signatures). The API key
 * alone is not enough, so a leaked key cannot restrict anybody.
 *
 * The signed message (must match sign() in ac-server/restrictions.js):
 *   owcmd1|command_id|type|user_id|issued_at|expires_at|reason_code|sha256hex(comment + "\n" + lift_notice)
 */
public final class CommandVerifier {
    private final PublicKey publicKey;

    public CommandVerifier(String publicKeyBase64) {
        this.publicKey = ScoreSignatureTracker.parseKey(publicKeyBase64);
    }

    public boolean isEnabled() {
        return publicKey != null;
    }

    public static String canonicalMessage(String commandId, String type, long userId, long issuedAt, long expiresAt,
                                          String reasonCode, String comment, String liftNotice) {
        return "owcmd1|" + commandId + "|" + type + "|" + userId + "|" + issuedAt + "|" + expiresAt + "|" + reasonCode
                + "|" + sha256Hex(comment + "\n" + liftNotice);
    }

    public boolean verify(String message, String signatureBase64) {
        if (publicKey == null || signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }

        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
