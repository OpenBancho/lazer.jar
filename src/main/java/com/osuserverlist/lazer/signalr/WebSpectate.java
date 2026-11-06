package com.osuserverlist.lazer.signalr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;

/**
 * Live spectating of lazer players from the website.
 *
 * <p>A lazer client streams its frames to the spectator hub whether anybody watches or not, so nothing has to be asked
 * of the client: the hub hands every {@code BeginPlaySession}, {@code SendFrameData} and {@code EndPlaySession} here,
 * this decodes the MessagePack into the same JSON events bancho.jar sends for stable players, and passes them to the
 * web viewers as server-sent events. The last seconds of frames are kept, so a viewer who opens the page in the middle
 * of a map sees it at once instead of waiting for the buffer to fill.
 *
 * <p>Events: {@code state} (online, playing, beatmap, mods, ruleset), {@code frames} (cursor frames
 * {@code [time, x, y, keys]} and the score header), {@code end} (passed, failed, quit).
 */
public final class WebSpectate {

    private static final Logger logger = LoggerFactory.getLogger(WebSpectate.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int MAX_PER_HOST = 200;
    private static final int MAX_TOTAL = 2000;

    /** How much gameplay a late viewer is given at once. */
    private static final double BACKLOG_MS = 12_000;
    private static final int BACKLOG_BUNDLES = 800;

    // SpectatedUserState
    private static final int PLAYING = 1;
    private static final int PAUSED = 2;
    private static final String[] STATE_NAMES = { "idle", "playing", "paused", "passed", "failed", "quit" };

    // HitResult, in enum order
    private static final String[] HIT_RESULTS = { "none", "miss", "meh", "ok", "good", "great", "perfect",
            "small_tick_miss", "small_tick_hit", "large_tick_miss", "large_tick_hit", "small_bonus", "large_bonus",
            "ignore_miss", "ignore_hit", "combo_break", "slider_tail_hit" };

    private final DatabaseManager databaseManager;
    private final IntPredicate online;

    private final Map<Integer, Set<Viewer>> viewers = new ConcurrentHashMap<>();
    private final Map<Integer, ObjectNode> states = new ConcurrentHashMap<>();
    private final Map<Integer, Deque<Bundle>> backlog = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();

    private final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "web-spectate-keepalive");
        t.setDaemon(true);
        return t;
    });

    private record Bundle(double lastTime, String json) {
    }

    private static final class Viewer {
        final SseClient client;
        final int hostId;

        Viewer(SseClient client, int hostId) {
            this.client = client;
            this.hostId = hostId;
        }

        synchronized boolean send(String event, String data) {
            if (client.terminated()) {
                return false;
            }

            try {
                client.sendEvent(event, data);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        synchronized boolean comment(String text) {
            if (client.terminated()) {
                return false;
            }

            try {
                client.sendComment(text);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * @param online whether a user is connected to the spectator hub at all
     */
    public WebSpectate(DatabaseManager databaseManager, IntPredicate online) {
        this.databaseManager = databaseManager;
        this.online = online;
        keepalive.scheduleAtFixedRate(this::heartbeat, 15, 15, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ HTTP

    /** {@code GET /api/v1/spectate/stream?id=} */
    public void stream(SseClient client) {
        int hostId;

        try {
            hostId = Integer.parseInt(client.ctx().queryParam("id"));
        } catch (Exception e) {
            client.sendEvent("error", "{\"error\":\"bad id\"}");
            client.close();
            return;
        }

        Viewer viewer = new Viewer(client, hostId);
        boolean[] admitted = { false };

        viewers.compute(hostId, (k, set) -> {
            if (set == null) {
                set = ConcurrentHashMap.newKeySet();
            }

            if (set.size() < MAX_PER_HOST && total.get() < MAX_TOTAL) {
                set.add(viewer);
                total.incrementAndGet();
                admitted[0] = true;
            }

            return set.isEmpty() ? null : set;
        });

        if (!admitted[0]) {
            client.sendEvent("error", "{\"error\":\"too many viewers\"}");
            client.close();
            return;
        }

        client.keepAlive();
        client.onClose(() -> remove(viewer));

        viewer.send("state", status(hostId).toString());

        if (visible(hostId)) {
            Deque<Bundle> held = backlog.get(hostId);

            if (held != null) {
                List<Bundle> copy;

                synchronized (held) {
                    copy = new ArrayList<>(held);
                }

                for (Bundle b : copy) {
                    viewer.send("frames", b.json());
                }
            }
        }
    }

    /** {@code GET /api/v1/spectate/status?id=} */
    public void handleStatus(Context ctx) {
        int id;

        try {
            id = Integer.parseInt(ctx.queryParam("id"));
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "id is required"));
            return;
        }

        ctx.header("Cache-Control", "no-store");
        ctx.contentType("application/json").result(status(id).toString());
    }

    private void remove(Viewer viewer) {
        viewers.computeIfPresent(viewer.hostId, (k, set) -> {
            if (set.remove(viewer)) {
                total.decrementAndGet();
            }

            return set.isEmpty() ? null : set;
        });
    }

    /** A restricted account is not shown to the public anywhere, its live play included. */
    private boolean visible(int userId) {
        try {
            User u = databaseManager.findUserById(userId);
            return u != null && (u.privileges & 1) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    public ObjectNode status(int userId) {
        boolean visible = visible(userId);
        ObjectNode state = visible ? states.get(userId) : null;
        ObjectNode node = state != null ? state.deepCopy() : JSON.createObjectNode();

        node.put("source", "lazer");
        node.put("user_id", userId);
        node.put("online", visible && online.test(userId));

        if (state == null) {
            node.put("playing", false);
        }

        node.put("viewers", viewers.getOrDefault(userId, Set.of()).size());
        return node;
    }

    // ------------------------------------------------------------------ hooks from the hub

    public void onBegin(int userId, Value state) {
        ObjectNode node = decodeState(userId, state);
        states.put(userId, node);
        backlog.put(userId, new ArrayDeque<>());

        broadcast(userId, "state", status(userId).toString());
    }

    public void onFrames(int userId, Value bundle) {
        Bundle decoded = decodeBundle(bundle);

        if (decoded == null) {
            return;
        }

        Deque<Bundle> held = backlog.get(userId);

        if (held != null) {
            synchronized (held) {
                held.addLast(decoded);

                while (held.size() > BACKLOG_BUNDLES
                        || (held.size() > 1 && decoded.lastTime() - held.peekFirst().lastTime() > BACKLOG_MS)) {
                    held.pollFirst();
                }
            }
        }

        broadcast(userId, "frames", decoded.json());
    }

    /** @param finalState SpectatedUserState: 3 passed, 4 failed, 5 quit */
    public void onEnd(int userId, int finalState) {
        states.remove(userId);
        backlog.remove(userId);

        String reason = finalState >= 0 && finalState < STATE_NAMES.length ? STATE_NAMES[finalState] : "quit";
        broadcast(userId, "end", "{\"source\":\"lazer\",\"reason\":\"" + reason + "\"}");
        broadcast(userId, "state", status(userId).toString());
    }

    /** The user's last hub connection closed. */
    public void onOffline(int userId) {
        broadcast(userId, "state", status(userId).toString());
    }

    private void broadcast(int userId, String event, String data) {
        Set<Viewer> set = viewers.get(userId);

        if (set == null || set.isEmpty() || !visible(userId)) {
            return;
        }

        for (Viewer v : set) {
            if (!v.send(event, data)) {
                remove(v);
            }
        }
    }

    private void heartbeat() {
        try {
            for (Set<Viewer> set : viewers.values()) {
                for (Viewer v : set) {
                    if (!v.comment("ping")) {
                        remove(v);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Web spectate heartbeat failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ MessagePack -> JSON

    // SpectatorState: [BeatmapID, RulesetID, Mods[[Acronym, Settings]], State, MaximumStatistics]
    private static ObjectNode decodeState(int userId, Value value) {
        ObjectNode node = JSON.createObjectNode();
        node.put("source", "lazer");
        node.put("user_id", userId);

        List<Value> f = list(value);
        int beatmapId = (int) num(at(f, 0), 0);
        int ruleset = (int) num(at(f, 1), 0);
        int state = (int) num(at(f, 3), PLAYING);

        node.put("beatmap_id", beatmapId);
        node.put("mode", ruleset);
        node.put("state", state >= 0 && state < STATE_NAMES.length ? STATE_NAMES[state] : "playing");
        node.put("playing", state == PLAYING || state == PAUSED);

        ArrayNode acronyms = node.putArray("mods");
        double rate = 1;

        for (Value mod : list(at(f, 2))) {
            List<Value> m = list(mod);
            String acronym = str(at(m, 0)).toUpperCase(Locale.ROOT);

            if (acronym.isEmpty()) {
                continue;
            }

            acronyms.add(acronym);

            double speed = setting(at(m, 1), "speed_change");

            switch (acronym) {
                case "DT", "NC" -> rate = speed > 0 ? speed : 1.5;
                case "HT", "DC" -> rate = speed > 0 ? speed : 0.75;
                case "WU", "WD", "AS" -> {
                    // a rate that changes during the map; the frames carry the real time anyway
                }
                default -> {
                }
            }

            if (acronym.equals("DA")) {
                // difficulty adjust: the renderer applies these over the map's own values
                ObjectNode da = node.putObject("difficulty");
                for (String key : new String[] { "circle_size", "approach_rate", "overall_difficulty", "drain_rate" }) {
                    double v = setting(at(m, 1), key);
                    if (v > 0) {
                        da.put(key, v);
                    }
                }
            }
        }

        node.put("rate", rate);
        return node;
    }

    // FrameDataBundle: [Header[TotalScore, Accuracy, Combo, MaxCombo, Statistics, ...], Frames[[Time, X, Y, Buttons]]]
    private static Bundle decodeBundle(Value value) {
        List<Value> f = list(value);
        List<Value> header = list(at(f, 0));
        List<Value> frames = list(at(f, 1));

        ObjectNode node = JSON.createObjectNode();
        node.put("source", "lazer");
        node.put("action", "standard");

        ArrayNode out = node.putArray("frames");
        double last = 0;
        double lastX = 256, lastY = 192;

        for (Value frame : frames) {
            List<Value> fr = list(frame);
            double time = num(at(fr, 0), Double.NaN);

            if (Double.isNaN(time)) {
                continue;
            }

            Value xv = at(fr, 1);
            Value yv = at(fr, 2);

            if (xv != null && !xv.isNilValue()) {
                lastX = num(xv, lastX);
            }

            if (yv != null && !yv.isNilValue()) {
                lastY = num(yv, lastY);
            }

            ArrayNode a = out.addArray();
            a.add(Math.round(time * 100) / 100.0);
            a.add(Math.round(lastX * 100) / 100.0);
            a.add(Math.round(lastY * 100) / 100.0);
            a.add((int) num(at(fr, 3), 0));
            last = Math.max(last, time);
        }

        if (!header.isEmpty()) {
            ObjectNode sc = node.putObject("score");
            sc.put("time", last);
            sc.put("score", (long) num(at(header, 0), 0));
            sc.put("accuracy", num(at(header, 1), 1));
            sc.put("combo", (int) num(at(header, 2), 0));
            sc.put("max_combo", (int) num(at(header, 3), 0));

            ObjectNode stats = sc.putObject("statistics");
            Value statValue = at(header, 4);

            if (statValue != null && statValue.isMapValue()) {
                for (Map.Entry<Value, Value> e : statValue.asMapValue().map().entrySet()) {
                    String key;

                    if (e.getKey().isIntegerValue()) {
                        int i = e.getKey().asIntegerValue().asInt();
                        key = i >= 0 && i < HIT_RESULTS.length ? HIT_RESULTS[i] : String.valueOf(i);
                    } else {
                        key = camelToSnake(str(e.getKey()));
                    }

                    stats.put(key, (int) num(e.getValue(), 0));
                }
            }

            // the stable names, so the page reads both servers the same way
            sc.put("n300", stats.path("great").asInt(0));
            sc.put("n100", stats.path("ok").asInt(0));
            sc.put("n50", stats.path("meh").asInt(0));
            sc.put("miss", stats.path("miss").asInt(0));
            sc.put("geki", stats.path("perfect").asInt(0));
            sc.put("katu", stats.path("good").asInt(0));
        }

        if (out.isEmpty() && header.isEmpty()) {
            return null;
        }

        return new Bundle(last, node.toString());
    }

    private static List<Value> list(Value v) {
        if (v == null || !v.isArrayValue()) {
            return List.of();
        }

        ArrayValue a = v.asArrayValue();
        return a.list();
    }

    private static Value at(List<Value> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }

    private static double num(Value v, double fallback) {
        if (v == null || v.isNilValue()) {
            return fallback;
        }

        if (v.isIntegerValue()) {
            return v.asIntegerValue().toLong();
        }

        if (v.isFloatValue()) {
            return v.asFloatValue().toDouble();
        }

        return fallback;
    }

    private static String str(Value v) {
        return v != null && v.isStringValue() ? v.asStringValue().asString() : "";
    }

    private static double setting(Value settings, String key) {
        if (settings == null || !settings.isMapValue()) {
            return 0;
        }

        for (Map.Entry<Value, Value> e : settings.asMapValue().map().entrySet()) {
            if (key.equals(str(e.getKey()))) {
                return num(e.getValue(), 0);
            }
        }

        return 0;
    }

    private static String camelToSnake(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
