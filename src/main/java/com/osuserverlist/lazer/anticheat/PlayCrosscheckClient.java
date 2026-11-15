package com.osuserverlist.lazer.anticheat;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tells the OpenWatcher anti-cheat server what this game server saw of a play as a spectator.
 *
 * The anti-cheat module inside the client reports every play to the anti-cheat server by itself (frames and raw input).
 * This server receives the same frames over the spectator hub, from the game, on a different path. The anti-cheat
 * compares the two: a module that was fed less than the game really produced does not match.
 *
 * Only a summary is sent (counts and times), never the frames. Configuration: AC_URL and AC_SERVER_KEY, like vouching.
 * Nothing here ever blocks or fails a score: the request runs on its own thread and a failure is only logged.
 */
public final class PlayCrosscheckClient {
    private static final Logger logger = LoggerFactory.getLogger(PlayCrosscheckClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "play-crosscheck");
        t.setDaemon(true);
        return t;
    });

    // Legacy replay button bits that are a key or mouse press in every ruleset's frames (left = 1, right = 2).
    private static final int[] PRESS_BITS = {1, 2};

    private static volatile String url = "";
    private static volatile String serverKey = "";

    private PlayCrosscheckClient() {}

    public static void configure(ServerConfig config) {
        url = config.acUrl == null ? "" : config.acUrl.trim().replaceAll("/+$", "");
        serverKey = config.acServerKey == null ? "" : config.acServerKey.trim();
        logger.info(isConfigured() ? "Play crosscheck with the anti-cheat is enabled" : "AC_URL / AC_SERVER_KEY are empty: plays are not crosschecked");
    }

    public static boolean isConfigured() {
        return !url.isEmpty() && !serverKey.isEmpty();
    }

    /** What the frames of one play add up to. frames: {time, x, y, buttons} in the order they arrived. */
    public record Summary(int frames, int presses, double firstTime, double lastTime) {}

    public static Summary summarize(List<float[]> frames) {
        int presses = 0;
        int previous = 0;
        double first = 0, last = 0;

        for (int i = 0; i < frames.size(); i++) {
            float[] f = frames.get(i);
            int buttons = (int) f[3];

            for (int bit : PRESS_BITS) {
                if ((buttons & bit) != 0 && (previous & bit) == 0) {
                    presses++;
                }
            }

            previous = buttons;
            if (i == 0) first = f[0];
            last = Math.max(last, f[0]);
        }

        return new Summary(frames.size(), presses, first, last);
    }

    /** Reports a play. frames may be empty: a score that was saved although this server never saw the play. */
    public static void report(long scoreToken, int userId, List<float[]> frames) {
        if (!isConfigured() || scoreToken <= 0) {
            return;
        }

        Summary summary = summarize(frames);

        SENDER.execute(() -> {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("token_id", scoreToken);
                body.put("user_id", userId);
                body.put("frames", summary.frames());
                body.put("presses", summary.presses());
                body.put("first_t", summary.firstTime());
                body.put("last_t", summary.lastTime());

                HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/v1/play/crosscheck"))
                        .timeout(Duration.ofSeconds(5))
                        .header("X-API-Key", serverKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build();

                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    logger.warn("Play crosscheck for score token {} was not accepted: HTTP {}", scoreToken, response.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.warn("Play crosscheck for score token {} failed: {}", scoreToken, e.toString());
            }
        });
    }
}
