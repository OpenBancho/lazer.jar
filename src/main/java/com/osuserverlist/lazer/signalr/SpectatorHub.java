package com.osuserverlist.lazer.signalr;

import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.online.OnlineManager;
import io.javalin.http.Context;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SpectatorHub {
    private static final Logger logger = LoggerFactory.getLogger(SpectatorHub.class);
    private static final byte RECORD_SEPARATOR = 0x1E;

    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final OnlineManager onlineManager;

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, Set<ClientSession>> userWatchers = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> sessionWatchedUsers = new ConcurrentHashMap<>();
    private final Map<Integer, Value> activeUserStates = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static class ClientSession {
        public final WsContext ws;
        public final int userId;
        public final String sessionId;
        public boolean handshaken = false;
        public boolean isMessagePack = true;

        public ClientSession(WsContext ws, int userId) {
            this.ws = ws;
            this.userId = userId;
            this.sessionId = ws.sessionId();
        }

        public void sendBinary(byte[] data) {
            try {
                ws.send(ByteBuffer.wrap(data));
            } catch (Exception e) {
                logger.debug("Failed to send binary packet to session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    public SpectatorHub(AuthService authService, DatabaseManager databaseManager, OnlineManager onlineManager) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.onlineManager = onlineManager;

        // Periodic keep-alive ping every 5 seconds
        this.scheduler.scheduleAtFixedRate(this::broadcastPeriodicPing, 5, 5, TimeUnit.SECONDS);
    }

    public void handleNegotiate(Context ctx) {
        String connectionId = UUID.randomUUID().toString();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("negotiateVersion", 1);
        resp.put("connectionId", connectionId);
        resp.put("connectionToken", connectionId);

        List<Map<String, Object>> transports = new ArrayList<>();
        Map<String, Object> wsTransport = new LinkedHashMap<>();
        wsTransport.put("transport", "WebSockets");
        wsTransport.put("transferFormats", List.of("Text", "Binary"));
        transports.add(wsTransport);

        resp.put("availableTransports", transports);

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }

    public void configureWs(WsConfig ws) {
        ws.onConnect(this::onConnect);
        ws.onMessage(this::onTextMessage);
        ws.onBinaryMessage(this::onBinaryMessage);
        ws.onClose(this::onClose);
        ws.onError(ctx -> logger.debug("SpectatorHub WebSocket error: {}", ctx.error() != null ? ctx.error().getMessage() : "unknown"));
    }

    private void onConnect(WsConnectContext ctx) {
        int userId = resolveUserId(ctx);
        ClientSession session = new ClientSession(ctx, userId);
        sessions.put(ctx.sessionId(), session);

        if (userId > 0) {
            onlineManager.markUserActive(userId);
            logger.info("User {} connected to SpectatorHub [{}]", userId, ctx.sessionId());
        }
    }

    private void onClose(WsCloseContext ctx) {
        ClientSession session = sessions.remove(ctx.sessionId());
        if (session != null) {
            // Remove watchers registered by this session
            Set<Integer> watched = sessionWatchedUsers.remove(ctx.sessionId());
            if (watched != null) {
                for (int targetId : watched) {
                    Set<ClientSession> watchers = userWatchers.get(targetId);
                    if (watchers != null) {
                        watchers.remove(session);
                        if (watchers.isEmpty()) {
                            userWatchers.remove(targetId);
                        }
                    }
                }
            }

            // Remove any session from userWatchers if matching
            for (Set<ClientSession> set : userWatchers.values()) {
                set.remove(session);
            }

            if (session.userId > 0) {
                logger.info("User {} disconnected from SpectatorHub [{}]", session.userId, ctx.sessionId());
            }
        }
    }

    private void onTextMessage(WsMessageContext ctx) {
        ClientSession session = sessions.get(ctx.sessionId());
        if (session == null) return;

        String msg = ctx.message();
        if (msg == null || msg.isEmpty()) return;

        if (!session.handshaken) {
            int sepIndex = msg.indexOf((char) RECORD_SEPARATOR);
            String handshakeJson = (sepIndex >= 0) ? msg.substring(0, sepIndex) : msg;
            session.handshaken = true;
            session.isMessagePack = handshakeJson.contains("messagepack");

            try {
                ctx.send("{}" + (char) RECORD_SEPARATOR);
            } catch (Exception e) {
                logger.warn("Failed to send SpectatorHub handshake ack: {}", e.getMessage());
            }

            if (session.userId > 0) {
                onlineManager.markUserActive(session.userId);
            }
        }
    }

    private void onBinaryMessage(WsBinaryMessageContext ctx) {
        ClientSession session = sessions.get(ctx.sessionId());
        if (session == null) return;

        ByteBuffer buf = ctx.data();
        if (buf == null || !buf.hasRemaining()) return;

        byte[] raw = new byte[buf.remaining()];
        buf.get(raw);

        if (!session.handshaken) {
            String text = new String(raw, StandardCharsets.UTF_8);
            if (text.contains("protocol")) {
                int sepIndex = text.indexOf((char) RECORD_SEPARATOR);
                String handshakeJson = (sepIndex >= 0) ? text.substring(0, sepIndex) : text;
                session.handshaken = true;
                session.isMessagePack = handshakeJson.contains("messagepack");
                try {
                    ctx.send("{}" + (char) RECORD_SEPARATOR);
                } catch (Exception e) {
                    logger.warn("Failed to send SpectatorHub handshake ack: {}", e.getMessage());
                }
                if (session.userId > 0) {
                    onlineManager.markUserActive(session.userId);
                }
                return;
            }
        }

        if (session.userId > 0) {
            onlineManager.markUserActive(session.userId);
        }

        try {
            ByteArrayInputStream in = new ByteArrayInputStream(raw);
            while (in.available() > 0) {
                int length = readVarInt(in);
                if (length <= 0 || in.available() < length) break;

                byte[] packet = new byte[length];
                in.read(packet, 0, length);

                handleMessagePackPacket(session, packet);
            }
        } catch (Exception e) {
            logger.debug("Error processing binary SpectatorHub packet: {}", e.getMessage());
        }
    }

    private void handleMessagePackPacket(ClientSession session, byte[] packet) {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packet)) {
            if (!unpacker.hasNext()) return;

            int arrayLen = unpacker.unpackArrayHeader();
            if (arrayLen < 1) return;

            int msgType = unpacker.unpackInt();

            if (msgType == 6) { // Ping
                sendPing(session);
                return;
            }

            if (msgType == 1) { // Invocation
                int headersCount = unpacker.unpackMapHeader();
                for (int i = 0; i < headersCount; i++) {
                    unpacker.unpackValue();
                    unpacker.unpackValue();
                }

                String invocationId = null;
                if (unpacker.getNextFormat() == org.msgpack.core.MessageFormat.NIL) {
                    unpacker.unpackNil();
                } else {
                    invocationId = unpacker.unpackString();
                }

                String target = unpacker.unpackString();
                int argCount = unpacker.hasNext() ? unpacker.unpackArrayHeader() : 0;

                handleHubInvocation(session, invocationId, target, unpacker, argCount);
            }
        } catch (Exception e) {
            logger.debug("Error handling SpectatorHub MessagePack packet: {}", e.getMessage());
        }
    }

    private void handleHubInvocation(ClientSession session, String invocationId, String target, MessageUnpacker unpacker, int argCount) throws IOException {
        logger.debug("SpectatorHub invoke target={} id={} args={} from user {}", target, invocationId, argCount, session.userId);

        if ("BeginPlaySessionV2".equalsIgnoreCase(target) || "BeginPlaySession".equalsIgnoreCase(target)) {
            handleBeginPlaySession(session, invocationId, unpacker, argCount);
            return;
        }

        if ("SendFrameDataV2".equalsIgnoreCase(target) || "SendFrameData".equalsIgnoreCase(target)) {
            handleSendFrameData(session, invocationId, target, unpacker, argCount);
            return;
        }

        if ("EndPlaySessionV2".equalsIgnoreCase(target) || "EndPlaySession".equalsIgnoreCase(target)) {
            handleEndPlaySession(session, invocationId, target, unpacker, argCount);
            return;
        }

        if ("StartWatchingUser".equalsIgnoreCase(target)) {
            handleStartWatchingUser(session, invocationId, unpacker, argCount);
            return;
        }

        if ("EndWatchingUser".equalsIgnoreCase(target)) {
            handleEndWatchingUser(session, invocationId, unpacker, argCount);
            return;
        }

        // Default: void completion
        if (invocationId != null) {
            sendVoidCompletion(session, invocationId);
        }
    }

    private void handleBeginPlaySession(ClientSession session, String invocationId, MessageUnpacker unpacker, int argCount) throws IOException {
        Value scoreTokenVal = null;
        Value stateVal = null;

        if (argCount >= 2) {
            scoreTokenVal = unpacker.unpackValue();
            stateVal = unpacker.unpackValue();
        } else if (argCount == 1) {
            stateVal = unpacker.unpackValue();
        }

        if (session.userId > 0 && stateVal != null) {
            activeUserStates.put(session.userId, stateVal);
            logger.info("User {} began playing in SpectatorHub", session.userId);

            // Broadcast UserBeganPlaying to all watchers
            broadcastUserBeganPlaying(session.userId, stateVal);
        }

        if (invocationId != null) {
            sendVoidCompletion(session, invocationId);
        }
    }

    private void handleSendFrameData(ClientSession session, String invocationId, String target, MessageUnpacker unpacker, int argCount) throws IOException {
        Value bundleVal = null;

        if ("SendFrameDataV2".equalsIgnoreCase(target)) {
            if (argCount >= 2) {
                unpacker.unpackValue(); // scoreToken
                bundleVal = unpacker.unpackValue(); // FrameDataBundle
            } else if (argCount == 1) {
                bundleVal = unpacker.unpackValue();
            }
        } else {
            if (argCount >= 1) {
                bundleVal = unpacker.unpackValue();
            }
        }

        if (session.userId > 0 && bundleVal != null) {
            broadcastUserSentFrames(session.userId, bundleVal);
        }

        if (invocationId != null) {
            sendVoidCompletion(session, invocationId);
        }
    }

    private void handleEndPlaySession(ClientSession session, String invocationId, String target, MessageUnpacker unpacker, int argCount) throws IOException {
        Value finalStateValue = null;

        if ("EndPlaySessionV2".equalsIgnoreCase(target)) {
            int finalStateInt = 2; // Passed default
            if (argCount >= 2) {
                unpacker.unpackValue(); // scoreToken
                Value fVal = unpacker.unpackValue();
                if (fVal.isIntegerValue()) {
                    finalStateInt = fVal.asIntegerValue().asInt();
                }
            } else if (argCount == 1) {
                Value fVal = unpacker.unpackValue();
                if (fVal.isIntegerValue()) {
                    finalStateInt = fVal.asIntegerValue().asInt();
                }
            }

            Value existingState = activeUserStates.get(session.userId);
            finalStateValue = createModifiedSpectatorState(existingState, finalStateInt);
        } else {
            if (argCount >= 1) {
                finalStateValue = unpacker.unpackValue();
            }
        }

        if (session.userId > 0) {
            if (finalStateValue != null) {
                broadcastUserFinishedPlaying(session.userId, finalStateValue);
            }
            activeUserStates.remove(session.userId);
            logger.info("User {} finished playing in SpectatorHub", session.userId);
        }

        if (invocationId != null) {
            sendVoidCompletion(session, invocationId);
        }
    }

    private void handleStartWatchingUser(ClientSession session, String invocationId, MessageUnpacker unpacker, int argCount) throws IOException {
        int targetUserId = 0;
        if (argCount >= 1 && unpacker.hasNext()) {
            Value uVal = unpacker.unpackValue();
            if (uVal.isIntegerValue()) {
                targetUserId = uVal.asIntegerValue().asInt();
            }
        }

        if (targetUserId > 0) {
            userWatchers.computeIfAbsent(targetUserId, k -> ConcurrentHashMap.newKeySet()).add(session);
            sessionWatchedUsers.computeIfAbsent(session.sessionId, k -> ConcurrentHashMap.newKeySet()).add(targetUserId);

            logger.debug("Session {} (user {}) started watching user {}", session.sessionId, session.userId, targetUserId);

            // If target user is already actively playing, immediately inform this watcher
            Value currentState = activeUserStates.get(targetUserId);
            if (currentState != null) {
                sendUserBeganPlaying(session, targetUserId, currentState);
            }
        }

        if (invocationId != null) {
            sendVoidCompletion(session, invocationId);
        }
    }

    private void handleEndWatchingUser(ClientSession session, String invocationId, MessageUnpacker unpacker, int argCount) throws IOException {
        int targetUserId = 0;
        if (argCount >= 1 && unpacker.hasNext()) {
            Value uVal = unpacker.unpackValue();
            if (uVal.isIntegerValue()) {
                targetUserId = uVal.asIntegerValue().asInt();
            }
        }

        if (targetUserId > 0) {
            Set<ClientSession> watchers = userWatchers.get(targetUserId);
            if (watchers != null) {
                watchers.remove(session);
                if (watchers.isEmpty()) {
                    userWatchers.remove(targetUserId);
                }
            }

            Set<Integer> watched = sessionWatchedUsers.get(session.sessionId);
            if (watched != null) {
                watched.remove(targetUserId);
            }
            logger.debug("Session {} (user {}) stopped watching user {}", session.sessionId, session.userId, targetUserId);
        }

        if (invocationId != null) {
            sendVoidCompletion(session, invocationId);
        }
    }

    public void broadcastUserBeganPlaying(int userId, Value stateValue) {
        Set<ClientSession> watchers = userWatchers.get(userId);
        if (watchers == null || watchers.isEmpty()) return;

        byte[] payload = buildHubInvocationMessage("UserBeganPlaying", userId, stateValue);
        for (ClientSession watcher : watchers) {
            watcher.sendBinary(payload);
        }
    }

    public void sendUserBeganPlaying(ClientSession targetSession, int userId, Value stateValue) {
        byte[] payload = buildHubInvocationMessage("UserBeganPlaying", userId, stateValue);
        targetSession.sendBinary(payload);
    }

    public void broadcastUserSentFrames(int userId, Value bundleValue) {
        Set<ClientSession> watchers = userWatchers.get(userId);
        if (watchers == null || watchers.isEmpty()) return;

        byte[] payload = buildHubInvocationMessage("UserSentFrames", userId, bundleValue);
        for (ClientSession watcher : watchers) {
            watcher.sendBinary(payload);
        }
    }

    public void broadcastUserFinishedPlaying(int userId, Value stateValue) {
        Set<ClientSession> watchers = userWatchers.get(userId);
        if (watchers == null || watchers.isEmpty()) return;

        byte[] payload = buildHubInvocationMessage("UserFinishedPlaying", userId, stateValue);
        for (ClientSession watcher : watchers) {
            watcher.sendBinary(payload);
        }
    }

    public void notifyScoreProcessed(int userId, long scoreId) {
        Set<ClientSession> watchers = userWatchers.get(userId);
        Set<ClientSession> targets = new HashSet<>();
        if (watchers != null) {
            targets.addAll(watchers);
        }
        for (ClientSession s : sessions.values()) {
            if (s.userId == userId) {
                targets.add(s);
            }
        }

        if (targets.isEmpty()) return;

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);

            // [1, {}, null, "UserScoreProcessed", [userId, scoreId]]
            packer.packArrayHeader(5);
            packer.packInt(1);
            packer.packMapHeader(0);
            packer.packNil();
            packer.packString("UserScoreProcessed");
            packer.packArrayHeader(2);
            packer.packInt(userId);
            packer.packLong(scoreId);
            packer.close();

            byte[] msg = encodeLengthPrefixed(baos.toByteArray());
            for (ClientSession target : targets) {
                target.sendBinary(msg);
            }
        } catch (Exception e) {
            logger.debug("Failed to broadcast UserScoreProcessed: {}", e.getMessage());
        }
    }

    private byte[] buildHubInvocationMessage(String target, int userId, Value payloadValue) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);

            // Invocation: [1, {}, null, target, [userId, payloadValue]]
            packer.packArrayHeader(5);
            packer.packInt(1);
            packer.packMapHeader(0);
            packer.packNil();
            packer.packString(target);

            packer.packArrayHeader(2);
            packer.packInt(userId);
            packer.packValue(payloadValue);

            packer.close();
            return encodeLengthPrefixed(baos.toByteArray());
        } catch (Exception e) {
            logger.debug("Error building hub message {}: {}", target, e.getMessage());
            return new byte[0];
        }
    }

    private Value createModifiedSpectatorState(Value existingState, int finalStateInt) {
        if (existingState != null && existingState.isArrayValue()) {
            ArrayValue arr = existingState.asArrayValue();
            List<Value> list = new ArrayList<>(arr.list());
            while (list.size() < 4) {
                list.add(ValueFactory.newNil());
            }
            list.set(3, ValueFactory.newInteger(finalStateInt));
            return ValueFactory.newArray(list);
        }

        List<Value> list = new ArrayList<>();
        list.add(ValueFactory.newNil()); // BeatmapID
        list.add(ValueFactory.newNil()); // RulesetID
        list.add(ValueFactory.newArray(Collections.emptyList())); // Mods
        list.add(ValueFactory.newInteger(finalStateInt)); // State
        list.add(ValueFactory.newMap(Collections.emptyMap())); // MaximumStatistics
        return ValueFactory.newArray(list);
    }

    private void sendVoidCompletion(ClientSession session, String invocationId) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);

            // [3, {}, invocationId, 2]
            packer.packArrayHeader(4);
            packer.packInt(3);
            packer.packMapHeader(0);
            packer.packString(invocationId);
            packer.packInt(2); // Void

            packer.close();
            byte[] msg = encodeLengthPrefixed(baos.toByteArray());
            session.sendBinary(msg);
        } catch (Exception e) {
            logger.debug("Failed to send void completion: {}", e.getMessage());
        }
    }

    private void sendPing(ClientSession session) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);
            packer.packArrayHeader(1);
            packer.packInt(6); // Ping
            packer.close();
            byte[] msg = encodeLengthPrefixed(baos.toByteArray());
            session.sendBinary(msg);
        } catch (Exception e) {
            logger.debug("Failed to send ping: {}", e.getMessage());
        }
    }

    private void broadcastPeriodicPing() {
        try {
            for (ClientSession session : sessions.values()) {
                if (!session.handshaken) continue;
                sendPing(session);
            }
        } catch (Exception e) {
            logger.debug("Error during spectator periodic ping: {}", e.getMessage());
        }
    }

    private int resolveUserId(WsContext ctx) {
        String token = ctx.queryParam("access_token");
        if (token == null || token.isBlank()) {
            String authHeader = ctx.header("Authorization");
            if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = authHeader.substring(7).trim();
            }
        }
        if (token != null && !token.isBlank()) {
            TokenStore.TokenData data = authService.resolveToken(token);
            if (data != null) {
                return data.userId;
            }
        }
        return -1;
    }

    private static byte[] encodeLengthPrefixed(byte[] payload) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeVarInt(baos, payload.length);
        baos.write(payload, 0, payload.length);
        return baos.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream stream, int value) {
        do {
            byte b = (byte) (value & 0x7F);
            value >>>= 7;
            if (value != 0) {
                b |= 0x80;
            }
            stream.write(b);
        } while (value != 0);
    }

    private static int readVarInt(ByteArrayInputStream stream) {
        int result = 0;
        int shift = 0;
        while (stream.available() > 0) {
            int b = stream.read();
            if (b == -1) break;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 35) {
                throw new IllegalArgumentException("Variable length quantity is too long");
            }
        }
        return result;
    }
}
