package com.osuserverlist.lazer.anticheat;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class ServerScoreCrypto {
    private static final Logger logger = LoggerFactory.getLogger(ServerScoreCrypto.class);

    public static final ushort_protocol PROTOCOL_VERSION = new ushort_protocol((short) 0x0001);
    public static final int TRANSCRIPT_SIZE = 146;
    public static final byte[] DOMAIN_TAG;

    private static final SecureRandom RANDOM = new SecureRandom();

    // Persistent Server Identity Keypair for Ed25519
    private static final Ed25519PrivateKeyParameters serverIdentityPrivateKey;
    public static final Ed25519PublicKeyParameters serverIdentityPublicKey;

    public static class ushort_protocol {
        public final short val;
        public ushort_protocol(short v) { this.val = v; }
    }

    static {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            DOMAIN_TAG = md.digest("OSULAZER_SCORE_HANDSHAKE_TRANSCRIPT_V1".getBytes(StandardCharsets.UTF_8));

            // Generate or load stable server identity Ed25519 keypair
            Ed25519KeyPairGenerator edGen = new Ed25519KeyPairGenerator();
            edGen.init(new Ed25519KeyGenerationParameters(RANDOM));
            var keyPair = edGen.generateKeyPair();
            serverIdentityPrivateKey = (Ed25519PrivateKeyParameters) keyPair.getPrivate();
            serverIdentityPublicKey = (Ed25519PublicKeyParameters) keyPair.getPublic();

            logger.info("Initialized ServerScoreCrypto with ServerIdentityPublicKey: {}",
                    Base64.getEncoder().encodeToString(serverIdentityPublicKey.getEncoded()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize ServerScoreCrypto", e);
        }
    }

    public static class HandshakeResult {
        public final long sessionId;
        public final long expiresAtEpochMs;
        public final byte[] serverChallenge;
        public final byte[] serverEphemeralPublicKey;
        public final byte[] signature;
        public final byte[] sessionKey;

        public HandshakeResult(long sessionId, long expiresAtEpochMs, byte[] serverChallenge,
                               byte[] serverEphemeralPublicKey, byte[] signature, byte[] sessionKey) {
            this.sessionId = sessionId;
            this.expiresAtEpochMs = expiresAtEpochMs;
            this.serverChallenge = serverChallenge;
            this.serverEphemeralPublicKey = serverEphemeralPublicKey;
            this.signature = signature;
            this.sessionKey = sessionKey;
        }
    }

    /**
     * Builds canonical 146-byte transcript:
     * [32 bytes DomainTag]
     * [2 bytes ProtocolVersion = 0x0001 (big endian)]
     * [8 bytes SessionId]
     * [8 bytes ExpiresAtEpochMs]
     * [32 bytes ServerChallenge]
     * [32 bytes ClientPublicKey]
     * [32 bytes ServerPublicKey]
     */
    public static byte[] buildTranscript(long sessionId, long expiresAtEpochMs,
                                         byte[] serverChallenge, byte[] clientPublicKey,
                                         byte[] serverPublicKey) {
        ByteBuffer buf = ByteBuffer.allocate(TRANSCRIPT_SIZE);
        buf.put(DOMAIN_TAG);
        buf.putShort((short) 0x0001);
        buf.putLong(sessionId);
        buf.putLong(expiresAtEpochMs);
        buf.put(serverChallenge);
        buf.put(clientPublicKey);
        buf.put(serverPublicKey);
        return buf.array();
    }

    /**
     * Performs server-side handshake:
     * 1. Generates ephemeral X25519 keypair and 32-byte challenge.
     * 2. Builds 146-byte Transcript.
     * 3. Signs Transcript with server Ed25519 private key.
     * 4. Calculates ECDH SharedSecret and derives 32-byte AES key via HKDF-SHA256.
     */
    public static HandshakeResult createHandshake(long sessionId, byte[] clientPublicKey, long ttlMs) {
        if (clientPublicKey == null || clientPublicKey.length != 32) {
            throw new IllegalArgumentException("Client public key must be 32 bytes");
        }

        // Ephemeral X25519 keypair
        X25519KeyPairGenerator xGen = new X25519KeyPairGenerator();
        xGen.init(new X25519KeyGenerationParameters(RANDOM));
        var xPair = xGen.generateKeyPair();
        X25519PrivateKeyParameters serverPriv = (X25519PrivateKeyParameters) xPair.getPrivate();
        X25519PublicKeyParameters serverPub = (X25519PublicKeyParameters) xPair.getPublic();

        byte[] serverChallenge = new byte[32];
        RANDOM.nextBytes(serverChallenge);

        long now = System.currentTimeMillis();
        long expiresAt = now + ttlMs;

        byte[] serverPubBytes = serverPub.getEncoded();
        byte[] transcript = buildTranscript(sessionId, expiresAt, serverChallenge, clientPublicKey, serverPubBytes);

        // Sign transcript with Ed25519
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, serverIdentityPrivateKey);
        signer.update(transcript, 0, transcript.length);
        byte[] signature = signer.generateSignature();

        // Calculate X25519 ECDH SharedSecret
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(serverPriv);
        byte[] sharedSecret = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(new X25519PublicKeyParameters(clientPublicKey, 0), sharedSecret, 0);

        // Derive AES-256 session key with HKDF-SHA256
        byte[] sessionKey = deriveSessionKey(sharedSecret, serverChallenge, DOMAIN_TAG);

        return new HandshakeResult(sessionId, expiresAt, serverChallenge, serverPubBytes, signature, sessionKey);
    }

    public static byte[] deriveSessionKey(byte[] sharedSecret, byte[] salt, byte[] info) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(sharedSecret, salt, info));
        byte[] okm = new byte[32];
        hkdf.generateBytes(okm, 0, 32);
        return okm;
    }

    /**
     * Builds canonical binary AAD:
     * [32 bytes DomainTag][8 bytes TokenId][4 bytes BeatmapId][4 bytes UserId][8 bytes Epoch]
     */
    public static byte[] buildAad(long tokenId, int beatmapId, int userId, long epochMs) {
        ByteBuffer buf = ByteBuffer.allocate(56);
        buf.put(DOMAIN_TAG);
        buf.putLong(tokenId);
        buf.putInt(beatmapId);
        buf.putInt(userId);
        buf.putLong(epochMs);
        return buf.array();
    }

    /**
     * Decrypts AES-256-GCM payload and verifies 16-byte authentication tag with canonical AAD.
     */
    public static byte[] decryptAead(byte[] sessionKey, byte[] nonce, byte[] tag, byte[] ciphertext, byte[] aad) throws Exception {
        if (sessionKey == null || sessionKey.length != 32) {
            throw new IllegalArgumentException("Session key must be 32 bytes");
        }

        byte[] combined = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
        System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(sessionKey, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }

        return cipher.doFinal(combined);
    }
}
