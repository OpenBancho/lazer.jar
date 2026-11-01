package com.osuserverlist.lazer.config;

import io.github.cdimascio.dotenv.Dotenv;

public class ServerConfig {
    public final int port;
    public final String level;
    public final String domain;
    public final String serverName;
    public final String serverVersion;

    public final String dbHost;
    public final int dbPort;
    public final String dbUser;
    public final String dbPass;
    public final String dbName;
    public final String dbTimezone;

    public final String redisHost;
    public final int redisPort;
    public final String redisPass;
    public final int redisDb;

    public ServerConfig(Dotenv dotenv) {
        this.port = Integer.parseInt(dotenv.get("PORT", "8000"));
        this.level = dotenv.get("LEVEL", "DEV");
        this.domain = dotenv.get("DOMAIN", "localhost:8000");
        this.serverName = dotenv.get("SERVER_NAME", "lazer.jar");
        this.serverVersion = dotenv.get("SERVER_VERSION", "1.0.0");

        this.dbHost = dotenv.get("DB_HOST", "localhost");
        this.dbPort = Integer.parseInt(dotenv.get("DB_PORT", "3306"));
        this.dbUser = dotenv.get("DB_USER", "bancho");
        this.dbPass = dotenv.get("DB_PASS", "changeme");
        this.dbName = dotenv.get("DB_NAME", "bancho");
        this.dbTimezone = dotenv.get("DB_TIMEZONE", "UTC");

        this.redisHost = dotenv.get("REDIS_HOST", "localhost");
        this.redisPort = Integer.parseInt(dotenv.get("REDIS_PORT", "6379"));
        this.redisPass = dotenv.get("REDIS_PASS", "");
        this.redisDb = Integer.parseInt(dotenv.get("REDIS_DB", "0"));

        this.osuApiKey = dotenv.get("OSU_API_KEY", "3f8616cb3488e3ed1cec4a8fd3501cebeb506ee5");
        this.directSearch = dotenv.get("DIRECT_SEARCH", "https://osu.direct/api/search");
        this.directSearchV2 = dotenv.get("DIRECT_SEARCH_V2", "https://catboy.best/api/v2/search");
        this.directDownload = dotenv.get("DIRECT_DOWNLOAD", "https://catboy.best/d");

        this.discordWebhookUrl = dotenv.get("DISCORD_WEBHOOK_URL", "");
        this.hmacSecret = dotenv.get("HMAC_SECRET", "default_lazer_secret_key");

        this.acWebhookFlags = dotenv.get("AC_WEBHOOK_FLAGS", "");
        this.acApiKeys = dotenv.get("AC_API_KEYS", "");
        this.acSigningPublicKey = dotenv.get("AC_SIGNING_PUBLIC_KEY", "");
        // CLIENT_MODE decides which client this server supports:
        //   strict    - only the anti-cheat client: every login needs the anti-cheat handshake ticket, scores need its signature
        //   nonstrict - plain client: the anti-cheat is not involved at all
        String clientMode = dotenv.get("CLIENT_MODE", "nonstrict").trim().toLowerCase();
        if (!clientMode.equals("strict") && !clientMode.equals("nonstrict")) {
            throw new IllegalStateException("CLIENT_MODE must be 'strict' or 'nonstrict', got '" + clientMode + "'");
        }
        this.strictClient = clientMode.equals("strict");
        // AC_UNSIGNED_ACTION: flag (default) only records the flag; hide also removes a score that the anti-cheat server
        // did not confirm (or whose signature is wrong) from the leaderboards.
        String unsignedAction = dotenv.get("AC_UNSIGNED_ACTION", "flag").trim().toLowerCase();
        if (!unsignedAction.equals("flag") && !unsignedAction.equals("hide")) {
            throw new IllegalStateException("AC_UNSIGNED_ACTION must be 'flag' or 'hide', got '" + unsignedAction + "'");
        }
        this.hideUnsignedScores = unsignedAction.equals("hide");
        if (this.strictClient && this.acSigningPublicKey.isBlank()) {
            throw new IllegalStateException("CLIENT_MODE=strict requires AC_SIGNING_PUBLIC_KEY (the anti-cheat server's public key)");
        }
        this.acSignatureTimeoutSeconds = Integer.parseInt(dotenv.get("AC_SIGNATURE_TIMEOUT_SECONDS", "120"));
        // Vouching: this server asks the anti-cheat about a player and vouches for one (see AnticheatVouchHandler).
        // Both empty = vouching is switched off; nothing else changes.
        this.acUrl = dotenv.get("AC_URL", "").trim();
        this.acServerKey = dotenv.get("AC_SERVER_KEY", "").trim();
    }

    public final String osuApiKey;
    public final String directSearch;
    public final String directSearchV2;
    public final String directDownload;

    public final String discordWebhookUrl;
    public final String hmacSecret;

    // Anti-cheat backend integration: flags arrive over the API (AC_API_KEYS = name:key,name2:key2)
    // and are logged to this webhook.
    public final String acWebhookFlags;
    public final String acApiKeys;
    // Ed25519 public key (base64) of the anti-cheat server; scores must be confirmed with its signature.
    public final String acSigningPublicKey;
    // true: strict server (anti-cheat client required), false: nonstrict server (anti-cheat not involved)
    public final boolean strictClient;
    public final boolean hideUnsignedScores;
    public final int acSignatureTimeoutSeconds;
    // The anti-cheat server's address and this game server's key there (INTERNAL_API_KEYS of the anti-cheat server).
    public final String acUrl;
    public final String acServerKey;
}
