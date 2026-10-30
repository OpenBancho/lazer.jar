package com.osuserverlist.lazer.anticheat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discord log for anti-cheat flags received from the anti-cheat backend.
 * Deliberately minimal: nickname, a short description of the flag and a hint about what it may be.
 * Everything else lives in the anti-cheat backend.
 */
public final class AnticheatFlagWebhook {
    private static final Logger logger = LoggerFactory.getLogger(AnticheatFlagWebhook.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int COLOR_FLAG = 0xE67E22;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "anticheat-flag-webhook");
        t.setDaemon(true);
        return t;
    });

    private final String webhookUrl;

    public AnticheatFlagWebhook(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void flag(String nickname, String summary, String hint) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", "Anti-cheat flag");
        embed.put("color", COLOR_FLAG);
        embed.put("fields", List.of(
                field("Player", nickname),
                field("Flag", summary),
                field("Hint", hint)));
        embed.put("timestamp", Instant.now().toString());

        SENDER.execute(() -> post(embed));
    }

    private static Map<String, Object> field(String name, String value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("value", value == null || value.isBlank() ? "-" : (value.length() > 500 ? value.substring(0, 497) + "..." : value));
        f.put("inline", false);
        return f;
    }

    private void post(Map<String, Object> embed) {
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
                    logger.warn("Anti-cheat flag webhook rejected: HTTP {}", response.statusCode());
                }
                return;
            }
        } catch (Exception e) {
            logger.warn("Anti-cheat flag webhook failed: {}", e.getMessage());
        }
    }
}
