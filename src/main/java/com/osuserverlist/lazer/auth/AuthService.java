package com.osuserverlist.lazer.auth;

import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final DatabaseManager databaseManager;
    private final TokenStore tokenStore;

    public AuthService(DatabaseManager databaseManager, TokenStore tokenStore) {
        this.databaseManager = databaseManager;
        this.tokenStore = tokenStore;
    }

    public TokenStore.TokenPair authenticatePassword(String username, String password, String scope) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return null;
        }

        User user = databaseManager.findUserByName(username.trim());
        if (user == null || user.passwordHash == null) {
            logger.warn("Authentication failed: user '{}' not found", username);
            return null;
        }

        String passwordMd5 = md5Hex(password);
        boolean passwordValid = checkPassword(user.passwordHash, passwordMd5);

        // Also check if password was already supplied as MD5 (osu! client legacy cases)
        if (!passwordValid && password.length() == 32 && password.matches("^[a-fA-F0-9]+$")) {
            passwordValid = checkPassword(user.passwordHash, password.toLowerCase());
        }

        if (!passwordValid) {
            logger.warn("Authentication failed: invalid password for user '{}'", username);
            return null;
        }

        logger.info("User '{}' (id: {}) successfully authenticated", user.name, user.id);
        return tokenStore.issue(user.id, user.name, user.privileges, scope);
    }

    public TokenStore.TokenPair authenticateRefresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        return tokenStore.refresh(refreshToken.trim());
    }

    public TokenStore.TokenData resolveToken(String bearerToken) {
        return tokenStore.resolve(bearerToken);
    }

    private boolean checkPassword(String storedHash, String passwordMd5) {
        try {
            return OpenBSDBCrypt.checkPassword(storedHash, passwordMd5.toCharArray());
        } catch (Exception e) {
            logger.error("Error verifying password hash: {}", e.getMessage());
            return false;
        }
    }

    private String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
