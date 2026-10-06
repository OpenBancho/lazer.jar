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
import org.msgpack.value.Value;
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

public class MetadataHub {
    private static final Logger logger = LoggerFactory.getLogger(MetadataHub.class);
    private static final byte RECORD_SEPARATOR = 0x1E;

    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final OnlineManager onlineManager;

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static class ClientSession {
        public final WsContext ws;
        public final int userId;
        public boolean handshaken = false;
        public boolean isMessagePack = true;
        public boolean watchingPresence = false;
        public int currentStatus = 2; // 2 = UserStatus.Online

        public ClientSession(WsContext ws, int userId) {
            this.ws = ws;
            this.userId = userId;
        }
    }

    public MetadataHub(AuthService authService, DatabaseManager databaseManager, OnlineManager onlineManager) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.onlineManager = onlineManager;

        // Periodic ping & presence sync to active watchers
        this.scheduler.scheduleAtFixedRate(this::broadcastPeriodicSync, 5, 5, TimeUnit.SECONDS);
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
        ws.onError(ctx -> logger.debug("MetadataHub WebSocket error: {}", ctx.error() != null ? ctx.error().getMessage() : "unknown"));
    }

    private void onConnect(WsConnectContext ctx) {
        int userId = resolveUserId(ctx);
        ClientSession session = new ClientSession(ctx, userId);
        sessions.put(ctx.sessionId(), session);

        if (userId > 0) {
            onlineManager.markUserActive(userId);
            logger.info("User {} connected to MetadataHub [{}]", userId, ctx.sessionId());
        }
    }

    private void onClose(WsCloseContext ctx) {
        ClientSession session = sessions.remove(ctx.sessionId());
        if (session != null && session.userId > 0) {
            logger.info("User {} disconnected from MetadataHub [{}]", session.userId, ctx.sessionId());
        }
    }

    private void onTextMessage(WsMessageContext ctx) {
        ClientSession session = sessions.get(ctx.sessionId());
        if (session == null) return;

        String msg = ctx.message();
        if (msg == null || msg.isEmpty()) return;

        // Handshake phase
        if (!session.handshaken) {
            int sepIndex = msg.indexOf((char) RECORD_SEPARATOR);
            String handshakeJson = (sepIndex >= 0) ? msg.substring(0, sepIndex) : msg;
            session.handshaken = true;
            session.isMessagePack = handshakeJson.contains("messagepack");

            // Complete handshake: send empty JSON object + 0x1E
            try {
                ctx.send("{}" + (char) RECORD_SEPARATOR);
            } catch (Exception e) {
                logger.warn("Failed to send handshake ack: {}", e.getMessage());
            }

            if (session.userId > 0) {
                onlineManager.markUserActive(session.userId);
            }
            return;
        }

        // Handle JSON SignalR invocations if text is used
        if (msg.contains("BeginWatchingUserPresence")) {
            session.watchingPresence = true;
            sendInitialPresences(session);
        }
    }

    private void onBinaryMessage(WsBinaryMessageContext ctx) {
        ClientSession session = sessions.get(ctx.sessionId());
        if (session == null) return;

        java.nio.ByteBuffer buf = ctx.data();
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
                logger.info("SignalR handshake (binary frame) completed for session {}", ctx.sessionId());
                try {
                    ctx.send("{}" + (char) RECORD_SEPARATOR);
                } catch (Exception e) {
                    logger.warn("Failed to send handshake ack: {}", e.getMessage());
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
            logger.debug("Error processing binary SignalR packet: {}", e.getMessage());
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
                // Headers map
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
            logger.debug("Error handling MessagePack hub packet: {}", e.getMessage());
        }
    }

    private void handleHubInvocation(ClientSession session, String invocationId, String target, MessageUnpacker unpacker, int argCount) throws IOException {
        logger.debug("MetadataHub invoke target={} id={}", target, invocationId);

        if ("GetChangesSince".equalsIgnoreCase(target)) {
            int queueId = -1;
            if (argCount > 0 && unpacker.hasNext()) {
                queueId = unpacker.unpackInt();
            }
            sendBeatmapChangesCompletion(session, invocationId, queueId);
            return;
        }

        if ("BeginWatchingUserPresence".equalsIgnoreCase(target)) {
            session.watchingPresence = true;
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            sendInitialPresences(session);
            return;
        }

        if ("EndWatchingUserPresence".equalsIgnoreCase(target)) {
            session.watchingPresence = false;
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("UpdateActivity".equalsIgnoreCase(target)) {
            if (argCount > 0 && unpacker.hasNext()) {
                Value val = unpacker.unpackValue();
            }
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("UpdateStatus".equalsIgnoreCase(target)) {
            if (argCount > 0 && unpacker.hasNext()) {
                try {
                    session.currentStatus = unpacker.unpackInt();
                } catch (Exception ignored) {}
            }
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("RefreshFriends".equalsIgnoreCase(target)) {
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            sendFriendPresences(session);
            return;
        }

        // Default: void completion
        if (invocationId != null) {
            sendVoidCompletion(session, invocationId);
        }
    }

    private void sendInitialPresences(ClientSession session) {
        Set<Integer> onlineIds = onlineManager.getCombinedOnlineUserIds();
        if (session.userId > 0) {
            onlineIds.add(session.userId);
        }

        for (int uid : onlineIds) {
            sendUserPresence(session, uid, 2); // 2 = Online
        }

        sendFriendPresences(session);
    }

    private void sendFriendPresences(ClientSession session) {
        if (session.userId <= 0) return;
        List<Integer> friends = databaseManager.findFriendUserIds(session.userId);
        for (int friendId : friends) {
            boolean isOnline = onlineManager.isUserOnline(friendId);
            sendFriendPresence(session, friendId, isOnline ? 2 : 0);
        }
    }

    public void sendUserPresence(ClientSession session, int userId, int status) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);

            // Invocation: [1, {}, null, "UserPresenceUpdated", [userId, [null, status]]]
            packer.packArrayHeader(5);
            packer.packInt(1);
            packer.packMapHeader(0);
            packer.packNil();
            packer.packString("UserPresenceUpdated");

            packer.packArrayHeader(2);
            packer.packInt(userId);

            // UserPresence struct: [Activity (null), Status (status)]
            if (status <= 0) {
                packer.packNil();
            } else {
                packer.packArrayHeader(2);
                packer.packNil(); // Activity = null
                packer.packInt(status); // Status
            }

            packer.close();
            byte[] msg = encodeLengthPrefixed(baos.toByteArray());
            session.ws.send(ByteBuffer.wrap(msg));
        } catch (Exception e) {
            logger.debug("Failed to send UserPresenceUpdated: {}", e.getMessage());
        }
    }

    public void sendFriendPresence(ClientSession session, int friendId, int status) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);

            packer.packArrayHeader(5);
            packer.packInt(1);
            packer.packMapHeader(0);
            packer.packNil();
            packer.packString("FriendPresenceUpdated");

            packer.packArrayHeader(2);
            packer.packInt(friendId);

            if (status <= 0) {
                packer.packNil();
            } else {
                packer.packArrayHeader(2);
                packer.packNil();
                packer.packInt(status);
            }

            packer.close();
            byte[] msg = encodeLengthPrefixed(baos.toByteArray());
            session.ws.send(ByteBuffer.wrap(msg));
        } catch (Exception e) {
            logger.debug("Failed to send FriendPresenceUpdated: {}", e.getMessage());
        }
    }

    private void sendBeatmapChangesCompletion(ClientSession session, String invocationId, int queueId) {
        if (invocationId == null) return;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);

            // Completion with result: [3, {}, invocationId, 3, BeatmapUpdates]
            packer.packArrayHeader(5);
            packer.packInt(3);
            packer.packMapHeader(0);
            packer.packString(invocationId);
            packer.packInt(3); // ResultKind = NonVoid

            // BeatmapUpdates: [LastProcessedQueueID, BeatmapSetIDs]
            packer.packArrayHeader(2);
            packer.packInt(queueId >= 0 ? queueId : 0);
            packer.packArrayHeader(0); // empty updates

            packer.close();
            byte[] msg = encodeLengthPrefixed(baos.toByteArray());
            session.ws.send(ByteBuffer.wrap(msg));
        } catch (Exception e) {
            logger.debug("Failed to send BeatmapChanges completion: {}", e.getMessage());
        }
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
            session.ws.send(ByteBuffer.wrap(msg));
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
            session.ws.send(ByteBuffer.wrap(msg));
        } catch (Exception e) {
            logger.debug("Failed to send ping: {}", e.getMessage());
        }
    }

    private void broadcastPeriodicSync() {
        try {
            Set<Integer> onlineIds = onlineManager.getCombinedOnlineUserIds();

            for (ClientSession session : sessions.values()) {
                if (!session.handshaken) continue;

                // Send ping to keep WebSocket connection alive
                sendPing(session);

                if (session.watchingPresence) {
                    for (int uid : onlineIds) {
                        sendUserPresence(session, uid, 2);
                    }
                    if (session.userId > 0) {
                        sendUserPresence(session, session.userId, 2);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error during periodic metadata sync: {}", e.getMessage());
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
