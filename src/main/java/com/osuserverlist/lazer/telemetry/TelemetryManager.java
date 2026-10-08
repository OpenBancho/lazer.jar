package com.osuserverlist.lazer.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.TelemetryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class TelemetryManager {
    private static final Logger logger = LoggerFactory.getLogger(TelemetryManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ServerConfig config;
    private final DatabaseManager databaseManager;
    private final HttpClient httpClient;
    private final ExecutorService asyncExecutor;

    public TelemetryManager(ServerConfig config, DatabaseManager databaseManager) {
        this.config = config;
        this.databaseManager = databaseManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void processTelemetryAsync(int userId, String username, String ip, String rawFingerprintJson) {
        asyncExecutor.submit(() -> {
            try {
                processTelemetry(userId, username, ip, rawFingerprintJson);
            } catch (Exception e) {
                logger.error("Error processing telemetry for user {} ({}): {}", username, userId, e.getMessage(), e);
            }
        });
    }

    public void processTelemetry(int userId, String username, String ip, String rawFingerprintJson) {
        if (userId <= 0) return;

        TelemetryRecord record = new TelemetryRecord();
        record.userId = userId;
        record.username = username != null ? username : "";
        record.ip = ip != null ? ip : "";
        record.rawJson = rawFingerprintJson != null ? rawFingerprintJson : "{}";

        String rawHardwareHash = "";

        if (rawFingerprintJson != null && !rawFingerprintJson.isBlank()) {
            try {
                JsonNode json = MAPPER.readTree(rawFingerprintJson);
                if (json.has("raw_hardware_hash")) rawHardwareHash = json.get("raw_hardware_hash").asText("");
                if (json.has("hardware_hash") && rawHardwareHash.isBlank()) rawHardwareHash = json.get("hardware_hash").asText("");

                if (json.has("os")) record.os = json.get("os").asText();
                if (json.has("version")) record.lazerVersion = json.get("version").asText();
                if (json.has("arch")) record.arch = json.get("arch").asText();
                if (json.has("cpu")) record.cpu = json.get("cpu").asText();
                if (json.has("cores")) record.cores = json.get("cores").asInt();
                if (json.has("gpu")) record.gpu = json.get("gpu").asText();
                if (json.has("ram_mb")) record.ramMb = json.get("ram_mb").asInt();
                if (json.has("resolution")) record.resolution = json.get("resolution").asText();
                if (json.has("refresh_rate")) record.refreshRate = json.get("refresh_rate").asInt();
                if (json.has("locale")) record.locale = json.get("locale").asText();
                if (json.has("timezone")) record.timezone = json.get("timezone").asText();
            } catch (Exception e) {
                logger.warn("Could not parse fingerprint JSON for user {}: {}", username, e.getMessage());
            }
        }

        // Compute HMAC-SHA256 for system fingerprint
        record.hardwareHash = rawHardwareHash;
        if (!rawHardwareHash.isBlank()) {
            record.systemFingerprint = hmacSha256Hex(config.hmacSecret, rawHardwareHash);
        } else {
            record.systemFingerprint = "";
        }

        // Check for previous records to detect drift
        List<TelemetryRecord> previousRecords = databaseManager.getRecentTelemetry(userId, 5);

        // Save new record to database
        databaseManager.saveTelemetry(record);

        // 1. Check for Multi-Account Match by System Fingerprint
        if (!record.systemFingerprint.isBlank()) {
            List<TelemetryRecord> matchedByFp = databaseManager.findOtherAccountsByFingerprint(record.systemFingerprint, userId);
            if (!matchedByFp.isEmpty()) {
                sendMultiAccountAlert(record, matchedByFp, "Hardware Fingerprint (HMAC Match)");
                return;
            }
        }

        // 2. Check for Hardware Drift / Spoofing Suspicion
        if (!previousRecords.isEmpty()) {
            TelemetryRecord prev = previousRecords.get(0);
            String driftReason = checkHardwareDrift(prev, record);
            if (driftReason != null) {
                sendHardwareDriftAlert(record, prev, driftReason);
                return;
            }
        }

        // 3. Check for Shared IP match
        if (record.ip != null && !record.ip.isBlank() && !"127.0.0.1".equals(record.ip)) {
            List<TelemetryRecord> matchedByIp = databaseManager.findOtherAccountsByIp(record.ip, userId);
            if (!matchedByIp.isEmpty()) {
                // Send info signal if 2+ other distinct accounts on the same IP
                Set<Integer> otherUserIds = matchedByIp.stream().map(r -> r.userId).collect(Collectors.toSet());
                if (otherUserIds.size() >= 2) {
                    sendIpMatchAlert(record, matchedByIp);
                }
            }
        }
    }

    private String checkHardwareDrift(TelemetryRecord prev, TelemetryRecord curr) {
        List<String> changes = new ArrayList<>();

        if (isSignificantChange(prev.cpu, curr.cpu)) {
            changes.add(String.format("CPU: '%s' -> '%s'", prev.cpu, curr.cpu));
        }
        if (isSignificantChange(prev.gpu, curr.gpu)) {
            changes.add(String.format("GPU: '%s' -> '%s'", prev.gpu, curr.gpu));
        }
        if (prev.ramMb > 0 && curr.ramMb > 0 && Math.abs(prev.ramMb - curr.ramMb) > 4096) {
            changes.add(String.format("RAM: %d MB -> %d MB", prev.ramMb, curr.ramMb));
        }
        if (isSignificantChange(prev.systemFingerprint, curr.systemFingerprint)) {
            changes.add("HWID / Fingerprint changed on same account");
        }

        if (changes.size() >= 2) {
            return String.join("; ", changes);
        }
        return null;
    }

    private boolean isSignificantChange(String a, String b) {
        if (a == null || b == null) return false;
        if (a.isBlank() || b.isBlank()) return false;
        return !a.equalsIgnoreCase(b);
    }

    private void sendMultiAccountAlert(TelemetryRecord current, List<TelemetryRecord> matches, String matchType) {
        String webhookUrl = config.discordWebhookUrl;
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        String otherAccounts = matches.stream()
                .map(m -> String.format("**%s** (ID: %d)", m.username, m.userId))
                .distinct()
                .collect(Collectors.joining(", "));

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", "Multi-Account Signal Detected");
        embed.put("color", 0xED4245); // Red
        embed.put("description", String.format("User **%s** matches the hardware fingerprint of other accounts.", current.username));

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(makeField("Current Account", String.format("%s (ID: %d)", current.username, current.userId), true));
        fields.add(makeField("Linked Accounts", otherAccounts, true));
        fields.add(makeField("Match Type", matchType, false));
        fields.add(makeField("IP Address", current.ip, true));
        fields.add(makeField("OS / Lazer", String.format("%s (%s)", nvl(current.os), nvl(current.lazerVersion)), true));
        fields.add(makeField("CPU / GPU", String.format("%s / %s", nvl(current.cpu), nvl(current.gpu)), false));
        fields.add(makeField("RAM / Display", String.format("%d MB / %s @ %dHz", current.ramMb, nvl(current.resolution), current.refreshRate), true));
        fields.add(makeField("HMAC Fingerprint", "`" + current.systemFingerprint + "`", false));

        embed.put("fields", fields);
        embed.put("timestamp", Instant.now().toString());
        embed.put("footer", Map.of("text", "lazer.jar Anti-Cheat Signals"));

        sendDiscordWebhook(embed);
    }

    private void sendHardwareDriftAlert(TelemetryRecord current, TelemetryRecord previous, String driftReason) {
        String webhookUrl = config.discordWebhookUrl;
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", "Hardware Drift / Possible Spoofing");
        embed.put("color", 0xE67E22); // Orange
        embed.put("description", String.format("Significant hardware configuration change detected for user **%s** (ID: %d).", current.username, current.userId));

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(makeField("User", String.format("%s (ID: %d)", current.username, current.userId), true));
        fields.add(makeField("IP Address", current.ip, true));
        fields.add(makeField("Detected Changes", driftReason, false));
        fields.add(makeField("Current Hardware", String.format("CPU: %s\nGPU: %s\nRAM: %d MB", nvl(current.cpu), nvl(current.gpu), current.ramMb), false));

        embed.put("fields", fields);
        embed.put("timestamp", Instant.now().toString());
        embed.put("footer", Map.of("text", "lazer.jar Anti-Cheat Signals"));

        sendDiscordWebhook(embed);
    }

    private void sendIpMatchAlert(TelemetryRecord current, List<TelemetryRecord> matches) {
        String webhookUrl = config.discordWebhookUrl;
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        String otherAccounts = matches.stream()
                .map(m -> String.format("**%s** (ID: %d)", m.username, m.userId))
                .distinct()
                .collect(Collectors.joining(", "));

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", "Shared IP Detected");
        embed.put("color", 0xFEE75C); // Yellow
        embed.put("description", String.format("User **%s** shares the same IP address with other accounts.", current.username));

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(makeField("Current Account", String.format("%s (ID: %d)", current.username, current.userId), true));
        fields.add(makeField("Linked Accounts (IP)", otherAccounts, true));
        fields.add(makeField("IP Address", current.ip, true));

        embed.put("fields", fields);
        embed.put("timestamp", Instant.now().toString());
        embed.put("footer", Map.of("text", "lazer.jar Anti-Cheat Signals"));

        sendDiscordWebhook(embed);
    }

    private Map<String, Object> makeField(String name, String value, boolean inline) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("value", value != null && !value.isBlank() ? value : "N/A");
        f.put("inline", inline);
        return f;
    }

    private void sendDiscordWebhook(Map<String, Object> embed) {
        String webhookUrl = config.discordWebhookUrl;
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        try {
            Map<String, Object> body = Map.of("embeds", List.of(embed));
            String jsonPayload = MAPPER.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(res -> {
                        if (res.statusCode() >= 400) {
                            logger.warn("Discord webhook returned HTTP status: {}", res.statusCode());
                        }
                    })
                    .exceptionally(err -> {
                        logger.error("Failed to post Discord webhook: {}", err.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Error creating Discord webhook request: {}", e.getMessage());
        }
    }

    private static String nvl(String val) {
        return (val == null || val.isBlank()) ? "N/A" : val;
    }

    public static String hmacSha256Hex(String secret, String data) {
        if (secret == null) secret = "";
        if (data == null) data = "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("Error calculating HMAC-SHA256: {}", e.getMessage());
            return "";
        }
    }
}
