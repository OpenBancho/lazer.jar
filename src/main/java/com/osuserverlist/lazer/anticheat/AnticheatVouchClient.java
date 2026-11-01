package com.osuserverlist.lazer.anticheat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Asks the OpenWatcher anti-cheat server about a player and vouches for one on behalf of this game server.
 *
 * The anti-cheat owns the restriction of a player it flagged and decides whether a score is signed. A game server can only ask what
 * it knows (status) and vouch for a player whose restriction is a heuristic one. A confirmed (hard) restriction is never vouchable:
 * the anti-cheat answers 409 hard_restriction and the owner appeals to OpenWatcher support. The client integrity checks are never
 * affected by a vouch.
 *
 * Configuration: AC_URL (the anti-cheat server) and AC_SERVER_KEY (this server's key there). Either empty = not configured.
 */
public class AnticheatVouchClient {
    private static final Logger logger = LoggerFactory.getLogger(AnticheatVouchClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** Status of the anti-cheat's answer (502 / 503 when it could not be reached / is not configured) and its JSON body. */
    public record Result(int status, JsonNode body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String error() {
            return body != null && body.hasNonNull("error") ? body.get("error").asText() : "The anti-cheat server did not answer.";
        }

        public String code() {
            return body != null && body.hasNonNull("code") ? body.get("code").asText() : "";
        }
    }

    private final String url;
    private final String serverKey;

    public AnticheatVouchClient(ServerConfig config) {
        this.url = config.acUrl == null ? "" : config.acUrl.trim().replaceAll("/+$", "");
        this.serverKey = config.acServerKey == null ? "" : config.acServerKey.trim();

        if (isConfigured()) {
            logger.info("Anti-cheat vouching enabled (AC_URL is set)");
        } else {
            logger.info("AC_URL / AC_SERVER_KEY are empty: vouching for players is disabled");
        }
    }

    public boolean isConfigured() {
        return !url.isEmpty() && !serverKey.isEmpty();
    }

    public Result status(int userId) {
        return send("GET", "/v1/server/status?user_id=" + userId, null);
    }

    public Result vouches() {
        return send("GET", "/v1/server/vouches?limit=200", null);
    }

    /** agreement: the word typed after reading the terms of vouching; termsVersion: the version of the terms that was shown (they come with status). */
    public Result vouch(int userId, String reason, String issuedBy, String agreement, int termsVersion) {
        Map<String, Object> body = body(userId, reason, issuedBy);
        body.put("agreement", agreement == null ? "" : agreement);
        body.put("terms_version", termsVersion);
        return send("POST", "/v1/server/vouch", body);
    }

    public Result revoke(int userId, String reason, String issuedBy) {
        return send("POST", "/v1/server/vouch/revoke", body(userId, reason, issuedBy));
    }

    private static Map<String, Object> body(int userId, String reason, String issuedBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("reason", reason == null ? "" : reason);
        body.put("issued_by", issuedBy);
        return body;
    }

    private Result send(String method, String path, Map<String, Object> payload) {
        if (!isConfigured()) {
            return failure(503, "The anti-cheat is not configured on this server (AC_URL / AC_SERVER_KEY).");
        }

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-API-Key", serverKey)
                    .header("Accept", "application/json");

            if (payload == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)));
            }

            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json;

            try {
                json = MAPPER.readTree(response.body());
            } catch (Exception e) {
                json = MAPPER.createObjectNode().put("error", "The anti-cheat server sent an unreadable answer.");
            }

            return new Result(response.statusCode(), json);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(502, "The anti-cheat request was interrupted.");
        } catch (Exception e) {
            logger.warn("Anti-cheat request {} {} failed: {}", method, path, e.toString());
            return failure(502, "The anti-cheat server could not be reached.");
        }
    }

    private static Result failure(int status, String message) {
        return new Result(status, MAPPER.createObjectNode().put("error", message));
    }
}
