package com.osuserverlist.lazer.anticheat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Checks the login ticket the anti-cheat server hands to a client that completed the anti-cheat handshake.
 * A client without anticheat.dll cannot obtain one, so it cannot log in while tickets are required.
 *
 * Ticket format: base64url(payload) + "." + base64url(Ed25519 signature of payload bytes),
 * payload = "owlogin1|id|expiresAtEpochSeconds|username" (username lower-case, "*" for token refreshes).
 * A ticket works once.
 */
public class LoginTicketVerifier {
    private static final Logger logger = LoggerFactory.getLogger(LoginTicketVerifier.class);

    private final PublicKey publicKey;
    private final boolean required;
    private final Map<String, Long> usedTickets = new HashMap<>();

    public LoginTicketVerifier(String publicKeyBase64, boolean requireTickets) {
        this.publicKey = ScoreSignatureTracker.parseKey(publicKeyBase64);
        this.required = requireTickets;

        if (requireTickets && publicKey == null) {
            // Fail closed: a strict server must never run without being able to check tickets.
            throw new IllegalStateException("CLIENT_MODE=strict but AC_SIGNING_PUBLIC_KEY is invalid");
        } else if (required) {
            logger.info("CLIENT_MODE=strict: every login needs a login ticket from the anti-cheat server");
        } else {
            logger.info("CLIENT_MODE=nonstrict: anti-cheat login tickets are not used");
        }
    }

    public boolean isRequired() {
        return required;
    }

    /**
     * @param username the account being logged in, or null for a token refresh
     * @return null when the ticket is acceptable, otherwise a short reason (for logs)
     */
    public String check(String ticket, String username) {
        if (ticket == null || ticket.isBlank()) return "no ticket";

        int dot = ticket.indexOf('.');
        if (dot <= 0 || dot == ticket.length() - 1) return "malformed ticket";

        String payload;
        byte[] signature;
        try {
            payload = new String(Base64.getUrlDecoder().decode(ticket.substring(0, dot)), StandardCharsets.UTF_8);
            signature = Base64.getUrlDecoder().decode(ticket.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return "malformed ticket";
        }

        if (!verify(payload, signature)) return "bad signature";

        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4 || !parts[0].equals("owlogin1")) return "unknown ticket format";

        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return "malformed ticket";
        }

        long now = System.currentTimeMillis() / 1000;
        if (now > expiresAt) return "expired ticket";

        String boundUser = parts[3];
        if (username != null && !boundUser.equals("*") && !boundUser.equalsIgnoreCase(username.trim())) {
            return "ticket was issued for another account";
        }
        if (username != null && boundUser.equals("*")) {
            return "ticket is not valid for a password login";
        }

        synchronized (this) {
            usedTickets.values().removeIf(exp -> exp < now);
            if (usedTickets.putIfAbsent(parts[1], expiresAt) != null) return "ticket was already used";
        }

        return null;
    }

    private boolean verify(String payload, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
