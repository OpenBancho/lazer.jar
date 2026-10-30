package com.osuserverlist.lazer.anticheat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.models.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends anti-cheat log embeds to Discord webhooks (one webhook per channel).
 * Channels: system (server lifecycle), clients (players connecting with the anti-cheat), flags (violations).
 * A channel without a configured webhook is simply skipped.
 */
public final class AnticheatLogger {
    private static final Logger logger = LoggerFactory.getLogger(AnticheatLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int COLOR_INFO = 0x3498DB;
    private static final int COLOR_OK = 0x2ECC71;
    private static final int COLOR_WARN = 0xE67E22;
    private static final int COLOR_STOP = 0x95A5A6;

    // A player is announced once per window, not on every score token.
    private static final long CLIENT_DEDUPE_MS = 30 * 60_000L;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "anticheat-discord-log");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentHashMap<Integer, Long> lastClientLog = new ConcurrentHashMap<>();

    private static ServerConfig config;
    private static long startedAtMs;

    private AnticheatLogger() {}

    public static void init(ServerConfig serverConfig) {
        config = serverConfig;
        startedAtMs = System.currentTimeMillis();
    }

    public static void serverStarted() {
        if (config == null) return;

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("Server", config.serverName + " " + config.serverVersion, true));
        fields.add(field("Domain", config.domain, true));
        fields.add(field("Level", config.level, true));
        fields.add(field("Host", hostName(), true));
        fields.add(field("Port", String.valueOf(config.port), true));
        fields.add(field("Runtime", "Java " + System.getProperty("java.version") + " / " + System.getProperty("os.name"), true));
        fields.add(field("Anti-cheat", "OpenWatcher handshake: **enabled**", true));
        fields.add(field("Identity key", "`" + identityFingerprint() + "`", true));

        send(config.acWebhookSystem, embed("Anti-cheat server online", COLOR_OK, fields));
    }

    public static void serverStopped() {
        if (config == null || blank(config.acWebhookSystem)) return;

        long upSeconds = (System.currentTimeMillis() - startedAtMs) / 1000;
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("Server", config.serverName + " (" + config.domain + ")", true));
        fields.add(field("Uptime", formatDuration(upSeconds), true));

        // Synchronous: the sender thread is a daemon and would be killed during shutdown.
        post(config.acWebhookSystem, embed("Anti-cheat server stopped", COLOR_STOP, fields));
    }

    public static void clientConnected(User user, String anticheatVersion, int beatmapId) {
        if (config == null || user == null || blank(config.acWebhookClients)) return;

        long now = System.currentTimeMillis();
        Long previous = lastClientLog.get(user.id);
        if (previous != null && now - previous < CLIENT_DEDUPE_MS) {
            return;
        }
        lastClientLog.put(user.id, now);

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("Player", user.name + " (`" + user.id + "`)", true));
        fields.add(field("Anti-cheat version", blank(anticheatVersion) ? "unknown" : anticheatVersion, true));
        fields.add(field("Handshake", "secure session established", true));
        fields.add(field("Beatmap", String.valueOf(beatmapId), true));
        fields.add(field("Server", config.serverName + " (" + config.domain + ")", true));

        send(config.acWebhookClients, embed("Client connected with anti-cheat", COLOR_INFO, fields));
    }

    public static void flagReceived(User user, String code, String reason, String clientVersion) {
        if (config == null || user == null || blank(config.acWebhookFlags)) return;

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("Player", user.name + " (`" + user.id + "`)", true));
        fields.add(field("Code", "`" + code + "`", true));
        fields.add(field("Client version", blank(clientVersion) ? "unknown" : clientVersion, true));
        fields.add(field("Server", config.serverName + " (" + config.domain + ")", true));
        fields.add(field("Reason", "```" + trim(reason, 900).replace("```", "'''") + "```", false));

        send(config.acWebhookFlags, embed("Anti-cheat flag received", COLOR_WARN, fields));
    }

    // ------------------------------------------------------------------

    private static Map<String, Object> embed(String title, int color, List<Map<String, Object>> fields) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("title", title);
        e.put("color", color);
        e.put("fields", fields);
        e.put("timestamp", Instant.now().toString());
        e.put("footer", Map.of("text", "OpenWatcher"));
        return e;
    }

    private static Map<String, Object> field(String name, String value, boolean inline) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("value", blank(value) ? "-" : trim(value, 1000));
        f.put("inline", inline);
        return f;
    }

    private static void send(String webhookUrl, Map<String, Object> embed) {
        if (blank(webhookUrl)) return;
        SENDER.execute(() -> post(webhookUrl, embed));
    }

    private static void post(String webhookUrl, Map<String, Object> embed) {
        try {
            String body = MAPPER.writeValueAsString(Map.of("embeds", List.of(embed)));
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    double retryAfter = response.headers().firstValue("Retry-After").map(Double::parseDouble).orElse(2.0);
                    Thread.sleep((long) (Math.min(retryAfter, 10.0) * 1000));
                    continue;
                }
                if (response.statusCode() >= 400) {
                    logger.warn("Anti-cheat Discord log rejected: HTTP {}", response.statusCode());
                }
                return;
            }
        } catch (Exception e) {
            logger.warn("Anti-cheat Discord log failed: {}", e.getMessage());
        }
    }

    private static String identityFingerprint() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(ServerScoreCrypto.serverIdentityPublicKey.getEncoded());
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String formatDuration(long seconds) {
        return String.format("%dh %02dm %02ds", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
