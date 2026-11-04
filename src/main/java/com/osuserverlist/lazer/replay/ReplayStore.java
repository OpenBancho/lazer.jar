package com.osuserverlist.lazer.replay;

import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds stable-format .osr replays out of the frames the lazer client streams to the spectator hub.
 *
 * The client tells the spectator hub which score token a play belongs to (BeginPlaySession) and later submits the
 * score over REST with the same token. A replay can only be written once both are known: the frames (play session
 * ended) and the saved score (id, statistics). Whichever arrives second triggers the write.
 *
 * Files land in data/replays/{scoreId}.osr, the same place bancho.jar keeps its replays, so both servers can serve
 * each other's replays from the shared database.
 */
public final class ReplayStore {
    private static final Logger logger = LoggerFactory.getLogger(ReplayStore.class);
    // Shared with bancho.jar (which serves them to the website and the stable client). Resolved by configure().
    private static volatile Path REPLAY_DIR = Paths.get("data", "replays");
    private static final long SESSION_TTL_MS = 3L * 60 * 60 * 1000;
    private static final int MAX_FRAMES = 400_000;
    private static final long TICKS_AT_UNIX_EPOCH = 621_355_968_000_000_000L;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "replay-writer");
        t.setDaemon(true);
        return t;
    });

    /** Score data needed for the .osr header, filled by the score submission. */
    public record ScoreInfo(long scoreId, int userId, String username, String beatmapMd5, int mode,
                            int n300, int n100, int n50, int ngeki, int nkatu, int nmiss,
                            long legacyScore, int maxCombo, boolean perfect, int mods, long playedAtEpochMs) {}

    private static final class Session {
        final int userId;
        final long token;
        final long createdAt = System.currentTimeMillis();
        final List<float[]> frames = new ArrayList<>(); // time, x, y, buttons
        boolean ended;
        ScoreInfo score;
        boolean written;

        Session(int userId, long token) {
            this.userId = userId;
            this.token = token;
        }
    }

    private static final Map<Long, Session> BY_TOKEN = new ConcurrentHashMap<>();
    private static final Map<Integer, Session> BY_USER = new ConcurrentHashMap<>();

    private ReplayStore() {}

    /**
     * Picks the replay folder: an explicit REPLAYS_DIR, else bancho.jar's data/replays when a bancho.jar checkout sits
     * next to this one (so stable and lazer replays live together), else ./data/replays.
     */
    public static void configure(String configured) {
        Path dir;
        if (configured != null && !configured.isBlank()) {
            dir = Paths.get(configured.trim());
        } else if (Files.isDirectory(Paths.get("..", "bancho.jar", "data"))) {
            dir = Paths.get("..", "bancho.jar", "data", "replays");
        } else {
            dir = Paths.get("data", "replays");
        }
        REPLAY_DIR = dir;
        logger.info("Replays are stored in {}", dir.toAbsolutePath().normalize());
    }

    public static Path replayPath(long scoreId) {
        return REPLAY_DIR.resolve(scoreId + ".osr");
    }

    public static boolean exists(long scoreId) {
        return Files.isRegularFile(replayPath(scoreId));
    }

    // ---- spectator side ----

    public static void begin(int userId, Long scoreToken) {
        long now = System.currentTimeMillis();
        BY_TOKEN.values().removeIf(s -> now - s.createdAt > SESSION_TTL_MS);
        BY_USER.values().removeIf(s -> now - s.createdAt > SESSION_TTL_MS);
        if (scoreToken == null || scoreToken <= 0) {
            BY_USER.remove(userId);
            return;
        }
        Session session = new Session(userId, scoreToken);
        BY_TOKEN.put(scoreToken, session);
        BY_USER.put(userId, session);
    }

    public static void addFrames(int userId, Value bundle) {
        Session session = BY_USER.get(userId);
        if (session == null || bundle == null || !bundle.isArrayValue()) return;
        ArrayValue root = bundle.asArrayValue();
        if (root.size() < 2 || !root.get(1).isArrayValue()) return;
        synchronized (session) {
            for (Value f : root.get(1).asArrayValue()) {
                if (session.frames.size() >= MAX_FRAMES) return;
                if (!f.isArrayValue()) continue;
                ArrayValue fa = f.asArrayValue();
                if (fa.size() < 4) continue;
                session.frames.add(new float[]{num(fa.get(0)), num(fa.get(1)), num(fa.get(2)), num(fa.get(3))});
            }
        }
    }

    public static void end(int userId) {
        Session session = BY_USER.remove(userId);
        if (session == null) return;
        synchronized (session) {
            session.ended = true;
        }
        tryWrite(session);
    }

    // ---- score submission side ----

    public static void onScoreSaved(long scoreToken, ScoreInfo info) {
        Session session = BY_TOKEN.get(scoreToken);
        if (session == null) return;
        synchronized (session) {
            session.score = info;
        }
        tryWrite(session);
    }

    private static void tryWrite(Session session) {
        final ScoreInfo info;
        final List<float[]> frames;
        synchronized (session) {
            if (session.written || !session.ended || session.score == null) return;
            if (session.frames.size() < 2) return;
            session.written = true;
            info = session.score;
            frames = new ArrayList<>(session.frames);
        }
        BY_TOKEN.remove(session.token);
        WRITER.execute(() -> {
            try {
                Files.createDirectories(REPLAY_DIR);
                byte[] osr = buildOsr(info, frames);
                Path tmp = REPLAY_DIR.resolve(info.scoreId() + ".osr.tmp");
                Files.write(tmp, osr);
                Files.move(tmp, replayPath(info.scoreId()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                logger.info("Stored replay for score {} ({} frames, {} bytes)", info.scoreId(), frames.size(), osr.length);
            } catch (Exception e) {
                logger.error("Failed to store replay for score {}: {}", info.scoreId(), e.getMessage());
            }
        });
    }

    // ---- .osr encoding ----

    /**
     * What a client gets when it downloads a replay. bancho.jar stores the stable client's upload as it is: the bare
     * LZMA frame stream, with no header. The lazer client cannot import that, it needs a complete .osr, so the header
     * is written here from the score row. A file that already is a complete .osr is returned untouched.
     */
    public static byte[] forDownload(com.osuserverlist.lazer.models.ScoreRecord sc, byte[] stored) throws IOException {
        if (isCompleteOsr(stored)) {
            return stored;
        }

        ScoreInfo info = new ScoreInfo(sc.id, sc.userId, sc.username, sc.mapMd5, sc.mode, sc.n300, sc.n100, sc.n50,
                sc.ngeki, sc.nkatu, sc.nmiss, sc.score, sc.maxCombo, sc.perfect, sc.mods, sc.playTimeEpochSec * 1000L);
        return buildOsrFromCompressed(info, stored);
    }

    /** A complete .osr starts with the game mode (0-3) and a four byte version; the bare stream with the LZMA 0x5D byte. */
    static boolean isCompleteOsr(byte[] data) {
        if (data == null || data.length < 12) {
            return false;
        }

        int mode = data[0] & 0xFF;
        int version = (data[1] & 0xFF) | (data[2] & 0xFF) << 8 | (data[3] & 0xFF) << 16 | (data[4] & 0xFF) << 24;
        return mode <= 3 && version >= 1000 && version < 100_000_000;
    }

    static byte[] buildOsr(ScoreInfo s, List<float[]> frames) throws IOException {
        return buildOsrFromCompressed(s, compress(frameText(frames).getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] buildOsrFromCompressed(ScoreInfo s, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);

        writeByte(out, s.mode() % 4);
        writeInt(out, 20240101);
        writeString(out, s.beatmapMd5());
        writeString(out, s.username());
        writeString(out, md5(s.scoreId() + ":" + s.beatmapMd5() + ":" + s.userId()));
        writeShort(out, s.n300());
        writeShort(out, s.n100());
        writeShort(out, s.n50());
        writeShort(out, s.ngeki());
        writeShort(out, s.nkatu());
        writeShort(out, s.nmiss());
        writeInt(out, (int) Math.min(s.legacyScore(), Integer.MAX_VALUE));
        writeShort(out, s.maxCombo());
        writeByte(out, s.perfect() ? 1 : 0);
        writeInt(out, s.mods());
        writeString(out, ""); // life bar graph is not tracked
        writeLong(out, s.playedAtEpochMs() * 10_000L + TICKS_AT_UNIX_EPOCH);

        writeInt(out, data.length);
        out.write(data);
        writeLong(out, s.scoreId());
        out.flush();
        return bytes.toByteArray();
    }

    private static String frameText(List<float[]> frames) {
        StringBuilder sb = new StringBuilder(frames.size() * 16);
        sb.append("0|256|-500|0,-1|256|-500|0,");
        long prev = 0;
        for (float[] f : frames) {
            long t = Math.round(f[0]);
            sb.append(t - prev).append('|').append(trim(f[1])).append('|').append(trim(f[2])).append('|')
                    .append((int) f[3]).append(',');
            prev = t;
        }
        sb.append("-12345|0|0|0");
        return sb.toString();
    }

    private static String trim(float v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static byte[] compress(byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // LZMA "alone" stream (5 property bytes + 8 byte size + data), which is what .osr stores
        try (LZMAOutputStream lzma = new LZMAOutputStream(bos, new LZMA2Options(), raw.length)) {
            lzma.write(raw);
        }
        return bos.toByteArray();
    }

    private static float num(Value v) {
        if (v == null || v.isNilValue()) return 0f;
        if (v.isIntegerValue()) return v.asIntegerValue().toLong();
        if (v.isFloatValue()) return (float) v.asFloatValue().toDouble();
        return 0f;
    }

    private static String md5(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // osu! binary primitives are little endian
    private static void writeByte(DataOutputStream o, int v) throws IOException { o.writeByte(v); }

    private static void writeShort(DataOutputStream o, int v) throws IOException {
        int s = Math.max(0, Math.min(v, 0xFFFF));
        o.writeByte(s & 0xFF);
        o.writeByte((s >> 8) & 0xFF);
    }

    private static void writeInt(DataOutputStream o, int v) throws IOException {
        o.writeInt(Integer.reverseBytes(v));
    }

    private static void writeLong(DataOutputStream o, long v) throws IOException {
        o.writeLong(Long.reverseBytes(v));
    }

    private static void writeString(DataOutputStream o, String s) throws IOException {
        if (s == null || s.isEmpty()) {
            o.writeByte(0x00);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeByte(0x0b);
        int len = b.length;
        do { // ULEB128 length
            int part = len & 0x7F;
            len >>>= 7;
            o.writeByte(len != 0 ? part | 0x80 : part);
        } while (len != 0);
        o.write(b);
    }
}
