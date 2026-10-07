package com.osuserverlist.lazer.signalr;

import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.MultiplayerRoomData.*;
import com.osuserverlist.lazer.multiplayer.MultiplayerManager;
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
import org.msgpack.value.MapValue;
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

public class MultiplayerHub {
    private static final Logger logger = LoggerFactory.getLogger(MultiplayerHub.class);
    private static final byte RECORD_SEPARATOR = 0x1E;

    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final MultiplayerManager multiplayerManager;
    private final OnlineManager onlineManager;

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, ClientSession> userSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static class ClientSession {
        public final WsContext ws;
        public final int userId;
        public boolean handshaken = false;
        public boolean isMessagePack = true;
        public long currentRoomId = 0;

        public ClientSession(WsContext ws, int userId) {
            this.ws = ws;
            this.userId = userId;
        }
    }

    public MultiplayerHub(AuthService authService, DatabaseManager databaseManager,
                          MultiplayerManager multiplayerManager, OnlineManager onlineManager) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.multiplayerManager = multiplayerManager;
        this.onlineManager = onlineManager;

        // Periodic keep-alive ping
        this.scheduler.scheduleAtFixedRate(this::broadcastPing, 5, 5, TimeUnit.SECONDS);
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
        ws.onError(ctx -> logger.debug("MultiplayerHub WebSocket error: {}", ctx.error() != null ? ctx.error().getMessage() : "unknown"));
    }

    private void onConnect(WsConnectContext ctx) {
        int userId = resolveUserId(ctx);
        ClientSession session = new ClientSession(ctx, userId);
        sessions.put(ctx.sessionId(), session);
        if (userId > 0) {
            userSessions.put(userId, session);
            onlineManager.markUserActive(userId);
            logger.info("User {} connected to MultiplayerHub [{}]", userId, ctx.sessionId());
        }
    }

    private void onClose(WsCloseContext ctx) {
        ClientSession session = sessions.remove(ctx.sessionId());
        if (session != null) {
            if (session.userId > 0) {
                userSessions.remove(session.userId);
                if (session.currentRoomId > 0) {
                    handleUserLeaveRoom(session.currentRoomId, session.userId);
                }
                logger.info("User {} disconnected from MultiplayerHub [{}]", session.userId, ctx.sessionId());
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
                logger.warn("Failed to send MultiplayerHub handshake ack: {}", e.getMessage());
            }
            return;
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
                logger.info("MultiplayerHub handshake (binary frame) completed for session {}", ctx.sessionId());
                try {
                    ctx.send("{}" + (char) RECORD_SEPARATOR);
                } catch (Exception e) {
                    logger.warn("Failed to send MultiplayerHub handshake ack: {}", e.getMessage());
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
            logger.debug("Error processing binary MultiplayerHub packet: {}", e.getMessage());
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
            logger.debug("Error handling MessagePack multiplayer packet: {}", e.getMessage());
        }
    }

    private void handleHubInvocation(ClientSession session, String invocationId, String target,
                                    MessageUnpacker unpacker, int argCount) throws IOException {
        logger.info("MultiplayerHub invoke target={} id={} userId={}", target, invocationId, session.userId);

        if ("CreateRoom".equalsIgnoreCase(target)) {
            String name = "Multiplayer Room";
            String password = "";
            int matchType = 1;
            int queueMode = 0;
            int beatmapId = 1;
            String checksum = "";
            int rulesetId = 0;
            List<Map<String, Object>> requiredMods = new ArrayList<>();
            List<Map<String, Object>> allowedMods = new ArrayList<>();

            if (argCount > 0 && unpacker.hasNext()) {
                try {
                    Value v = unpacker.unpackValue();
                    if (v.isArrayValue()) {
                        ArrayValue roomArr = v.asArrayValue();
                        // Key 2: Settings
                        if (roomArr.size() > 2 && !roomArr.get(2).isNilValue()) {
                            Value sv = roomArr.get(2);
                            if (sv.isArrayValue()) {
                                ArrayValue sArr = sv.asArrayValue();
                                if (sArr.size() > 0 && !sArr.get(0).isNilValue()) name = sArr.get(0).asStringValue().asString();
                                if (sArr.size() > 2 && !sArr.get(2).isNilValue()) password = sArr.get(2).asStringValue().asString();
                                if (sArr.size() > 3 && !sArr.get(3).isNilValue()) matchType = sArr.get(3).asIntegerValue().asInt();
                                if (sArr.size() > 4 && !sArr.get(4).isNilValue()) queueMode = sArr.get(4).asIntegerValue().asInt();
                            }
                        }
                        // Key 6: Playlist
                        if (roomArr.size() > 6 && !roomArr.get(6).isNilValue() && roomArr.get(6).isArrayValue()) {
                            ArrayValue plArr = roomArr.get(6).asArrayValue();
                            if (plArr.size() > 0) {
                                PlaylistItem item = parsePlaylistItem(plArr.get(0));
                                if (item != null) {
                                    if (item.beatmapId > 0) beatmapId = item.beatmapId;
                                    if (item.beatmapChecksum != null && !item.beatmapChecksum.isBlank()) {
                                        checksum = item.beatmapChecksum;
                                    }
                                    rulesetId = item.rulesetId;
                                    if (item.requiredMods != null) requiredMods.addAll(item.requiredMods);
                                    if (item.allowedMods != null) allowedMods.addAll(item.allowedMods);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error unpacking CreateRoom payload: {}", e.getMessage());
                }
            }

            Room room = multiplayerManager.createRoom(session.userId, name, password, matchType, queueMode,
                    beatmapId, checksum, rulesetId, requiredMods, allowedMods, "realtime");

            session.currentRoomId = room.roomId;
            if (invocationId != null) {
                sendRoomCompletion(session, invocationId, room);
            }
            return;
        }

        if ("JoinRoom".equalsIgnoreCase(target) || "JoinRoomWithPassword".equalsIgnoreCase(target)) {
            long roomId = 0;
            String password = "";
            if (argCount > 0 && unpacker.hasNext()) {
                roomId = unpacker.unpackLong();
            }
            if (argCount > 1 && unpacker.hasNext()) {
                password = unpacker.unpackString();
            }

            Room room = multiplayerManager.joinRoom(roomId, session.userId, password);
            if (room != null) {
                session.currentRoomId = room.roomId;
                if (invocationId != null) {
                    sendRoomCompletion(session, invocationId, room);
                }

                // Broadcast UserJoined to other members
                RoomUser joinedUser = room.users.get(session.userId);
                if (joinedUser != null) {
                    broadcastToRoomExcept(room, session.userId, "UserJoined", 1, packer -> packRoomUser(packer, joinedUser));
                }
            } else {
                if (invocationId != null) {
                    sendErrorCompletion(session, invocationId, "Failed to join room");
                }
            }
            return;
        }

        if ("LeaveRoom".equalsIgnoreCase(target)) {
            if (session.currentRoomId > 0) {
                handleUserLeaveRoom(session.currentRoomId, session.userId);
                session.currentRoomId = 0;
            }
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("ChangeSettings".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null && argCount > 0 && unpacker.hasNext()) {
                try {
                    Value v = unpacker.unpackValue();
                    RoomSettings newSettings = parseRoomSettings(v);
                    if (newSettings != null) {
                        int oldQueueMode = room.settings.queueMode;
                        if (newSettings.name != null && !newSettings.name.isBlank()) {
                            room.settings.name = newSettings.name;
                        }
                        room.settings.password = newSettings.password != null ? newSettings.password : "";
                        room.settings.matchType = newSettings.matchType;
                        room.settings.queueMode = newSettings.queueMode;
                        room.settings.autoStartDurationMs = newSettings.autoStartDurationMs;
                        room.settings.autoSkip = newSettings.autoSkip;
                        room.settings.maxParticipants = newSettings.maxParticipants;
                        if (newSettings.playlistItemId > 0 && room.hasPlaylistItem(newSettings.playlistItemId)) {
                            room.settings.playlistItemId = newSettings.playlistItemId;
                        }

                        if (oldQueueMode != newSettings.queueMode) {
                            updatePlaylistOrder(room);
                        }
                        updateCurrentItem(room);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to unpack settings: {}", e.getMessage());
                }

                if (invocationId != null) {
                    sendVoidCompletion(session, invocationId);
                }
                broadcastToRoom(room, "SettingsChanged", 1, packer -> packRoomSettings(packer, room.settings));
            } else if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("ChangeState".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null && argCount > 0 && unpacker.hasNext()) {
                int stateInt = unpacker.unpackInt();
                UserState newState = UserState.fromInt(stateInt);
                multiplayerManager.changeUserState(room.roomId, session.userId, newState);

                if (invocationId != null) {
                    sendVoidCompletion(session, invocationId);
                }
                broadcastToRoom(room, "UserStateChanged", 2, packer -> {
                    packer.packInt(session.userId);
                    packer.packInt(newState.value);
                });

                // When players finish loading into match and become ReadyForGameplay (or Loaded)
                if (room.state == RoomState.WAITING_FOR_LOAD) {
                    boolean allReady = true;
                    for (RoomUser u : room.users.values()) {
                        if (u.state != UserState.READY_FOR_GAMEPLAY && u.state != UserState.LOADED && u.state != UserState.SPECTATING) {
                            allReady = false;
                            break;
                        }
                    }
                    if (allReady && (newState == UserState.READY_FOR_GAMEPLAY || newState == UserState.LOADED)) {
                        for (RoomUser u : room.users.values()) {
                            if (u.state == UserState.READY_FOR_GAMEPLAY || u.state == UserState.LOADED) {
                                u.state = UserState.PLAYING;
                                broadcastToRoom(room, "UserStateChanged", 2, packer -> {
                                    packer.packInt(u.userId);
                                    packer.packInt(UserState.PLAYING.value);
                                });
                            }
                        }
                        room.state = RoomState.PLAYING;
                        broadcastToRoom(room, "RoomStateChanged", 1, packer -> packer.packInt(RoomState.PLAYING.value));
                        broadcastToRoom(room, "GameplayStarted", 0, null);
                        checkVoteToSkipIntro(room);
                    }
                }

                // Check if all playing users have finished gameplay
                if (room.state == RoomState.PLAYING || room.state == RoomState.WAITING_FOR_LOAD) {
                    if (newState == UserState.FINISHED_PLAY || newState == UserState.RESULTS) {
                        boolean allFinished = true;
                        for (RoomUser u : room.users.values()) {
                            if (u.state == UserState.PLAYING || u.state == UserState.WAITING_FOR_LOAD || u.state == UserState.READY_FOR_GAMEPLAY || u.state == UserState.LOADED) {
                                allFinished = false;
                                break;
                            }
                        }
                        if (allFinished) {
                            // 1. Transition FinishedPlay users to Results
                            for (RoomUser u : room.users.values()) {
                                if (u.state == UserState.FINISHED_PLAY) {
                                    u.state = UserState.RESULTS;
                                    broadcastToRoom(room, "UserStateChanged", 2, packer -> {
                                        packer.packInt(u.userId);
                                        packer.packInt(UserState.RESULTS.value);
                                    });
                                }
                            }

                            // 2. Transition room state back to Open
                            room.state = RoomState.OPEN;
                            broadcastToRoom(room, "RoomStateChanged", 1, packer -> packer.packInt(RoomState.OPEN.value));

                            // 3. Notify ResultsReady
                            broadcastToRoom(room, "ResultsReady", 0, null);

                            // 4. Progress playlist / item
                            finishCurrentPlaylistItem(room);
                        }
                    }
                }

                // If players returned to Idle, open room back up if not already open
                if (newState == UserState.IDLE || newState == UserState.READY) {
                    if (room.state != RoomState.OPEN) {
                        boolean anyonePlaying = false;
                        for (RoomUser u : room.users.values()) {
                            if (u.state == UserState.PLAYING || u.state == UserState.WAITING_FOR_LOAD || u.state == UserState.LOADED || u.state == UserState.READY_FOR_GAMEPLAY) {
                                anyonePlaying = true;
                                break;
                            }
                        }
                        if (!anyonePlaying) {
                            room.state = RoomState.OPEN;
                            broadcastToRoom(room, "RoomStateChanged", 1, packer -> packer.packInt(RoomState.OPEN.value));
                        }
                    }
                }
                return;
            } else if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("ChangeBeatmapAvailability".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null && argCount > 0 && unpacker.hasNext()) {
                BeatmapAvailability ba = new BeatmapAvailability();
                try {
                    int arrLen = unpacker.unpackArrayHeader();
                    if (arrLen >= 1) ba.state = unpacker.unpackInt();
                    if (arrLen >= 2) {
                        if (unpacker.getNextFormat() == org.msgpack.core.MessageFormat.NIL) unpacker.unpackNil();
                        else ba.downloadProgress = unpacker.unpackDouble();
                    }
                } catch (Exception ignored) {}
                multiplayerManager.changeBeatmapAvailability(room.roomId, session.userId, ba);

                if (invocationId != null) {
                    sendVoidCompletion(session, invocationId);
                }
                broadcastToRoom(room, "UserBeatmapAvailabilityChanged", 2, packer -> {
                    packer.packInt(session.userId);
                    packBeatmapAvailability(packer, ba);
                });
            } else if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("ChangeUserMods".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null && argCount > 0 && unpacker.hasNext()) {
                try {
                    Value v = unpacker.unpackValue();
                    List<Map<String, Object>> mods = parseMods(v);
                    multiplayerManager.changeUserMods(room.roomId, session.userId, mods);
                    if (invocationId != null) {
                        sendVoidCompletion(session, invocationId);
                    }
                    broadcastToRoom(room, "UserModsChanged", 2, packer -> {
                        packer.packInt(session.userId);
                        packMods(packer, mods);
                    });
                    return;
                } catch (Exception e) {
                    logger.debug("Failed to handle ChangeUserMods: {}", e.getMessage());
                }
            }
            if (invocationId != null) sendVoidCompletion(session, invocationId);
            return;
        }

        if ("ChangeUserStyle".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null) {
                Integer beatmapId = null;
                Integer rulesetId = null;
                try {
                    if (argCount > 0 && unpacker.hasNext()) {
                        Value v1 = unpacker.unpackValue();
                        if (!v1.isNilValue()) beatmapId = v1.asIntegerValue().asInt();
                    }
                    if (argCount > 1 && unpacker.hasNext()) {
                        Value v2 = unpacker.unpackValue();
                        if (!v2.isNilValue()) rulesetId = v2.asIntegerValue().asInt();
                    }
                } catch (Exception ignored) {}
                multiplayerManager.changeUserStyle(room.roomId, session.userId, beatmapId, rulesetId);
                if (invocationId != null) sendVoidCompletion(session, invocationId);
                final Integer fbm = beatmapId;
                final Integer frs = rulesetId;
                broadcastToRoom(room, "UserStyleChanged", 3, packer -> {
                    packer.packInt(session.userId);
                    if (fbm != null) packer.packInt(fbm); else packer.packNil();
                    if (frs != null) packer.packInt(frs); else packer.packNil();
                });
                return;
            }
            if (invocationId != null) sendVoidCompletion(session, invocationId);
            return;
        }

        if ("VoteToSkipIntro".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null) {
                RoomUser u = room.users.get(session.userId);
                if (u != null) {
                    u.votedToSkipIntro = true;
                    if (invocationId != null) sendVoidCompletion(session, invocationId);
                    broadcastToRoom(room, "UserVotedToSkipIntro", 2, packer -> {
                        packer.packInt(session.userId);
                        packer.packBoolean(true);
                    });
                    checkVoteToSkipIntro(room);
                    return;
                }
            }
            if (invocationId != null) sendVoidCompletion(session, invocationId);
            return;
        }

        if ("AddPlaylistItem".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null && argCount > 0 && unpacker.hasNext()) {
                try {
                    Value v = unpacker.unpackValue();
                    PlaylistItem item = parsePlaylistItem(v);
                    if (item != null) {
                        PlaylistItem added = multiplayerManager.addPlaylistItem(room.roomId, session.userId, item);
                        if (added != null) {
                            if (invocationId != null) {
                                sendVoidCompletion(session, invocationId);
                            }
                            broadcastToRoom(room, "PlaylistItemAdded", 1, packer -> packPlaylistItem(packer, added));

                            updatePlaylistOrder(room);
                            updateCurrentItem(room);
                            return;
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to add playlist item: {}", e.getMessage());
                }
            }
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("EditPlaylistItem".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null && argCount > 0 && unpacker.hasNext()) {
                try {
                    Value v = unpacker.unpackValue();
                    PlaylistItem item = parsePlaylistItem(v);
                    if (item != null) {
                        PlaylistItem edited = multiplayerManager.editPlaylistItem(room.roomId, session.userId, item);
                        if (edited != null) {
                            if (invocationId != null) {
                                sendVoidCompletion(session, invocationId);
                            }
                            broadcastToRoom(room, "PlaylistItemChanged", 1, packer -> packPlaylistItem(packer, edited));

                            // If this edited item is the current item, unready players so they download the new map
                            if (room.settings.playlistItemId == edited.id) {
                                for (RoomUser u : room.users.values()) {
                                    if (u.state == UserState.READY) {
                                        u.state = UserState.IDLE;
                                        broadcastToRoom(room, "UserStateChanged", 2, packer -> {
                                            packer.packInt(u.userId);
                                            packer.packInt(UserState.IDLE.value);
                                        });
                                    }
                                }
                            }
                            return;
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to edit playlist item: {}", e.getMessage());
                }
            }
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("RemovePlaylistItem".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null && argCount > 0 && unpacker.hasNext()) {
                try {
                    long itemId = unpacker.unpackLong();

                    PlaylistItem itemToRemove = null;
                    for (PlaylistItem it : room.playlist) {
                        if (it.id == itemId) {
                            itemToRemove = it;
                            break;
                        }
                    }

                    if (itemToRemove == null) {
                        if (invocationId != null) sendVoidCompletion(session, invocationId);
                        return;
                    }

                    // Check permissions: only item owner or room host can remove
                    if (itemToRemove.ownerId != session.userId && room.hostUserId != session.userId) {
                        logger.warn("User {} not allowed to remove playlist item {} in room {}", session.userId, itemId, room.roomId);
                        if (invocationId != null) sendVoidCompletion(session, invocationId);
                        return;
                    }

                    // Count active (unexpired) items excluding this one
                    List<PlaylistItem> remainingActive = new ArrayList<>();
                    for (PlaylistItem it : room.playlist) {
                        if (!it.expired && it.id != itemId) {
                            remainingActive.add(it);
                        }
                    }

                    // If removing would leave 0 active items in the room, create a replacement first
                    if (remainingActive.isEmpty()) {
                        PlaylistItem duplicate = new PlaylistItem();
                        duplicate.beatmapId = itemToRemove.beatmapId;
                        duplicate.beatmapChecksum = itemToRemove.beatmapChecksum;
                        duplicate.rulesetId = itemToRemove.rulesetId;
                        duplicate.requiredMods.addAll(itemToRemove.requiredMods);
                        duplicate.allowedMods.addAll(itemToRemove.allowedMods);
                        duplicate.starRating = itemToRemove.starRating;
                        duplicate.freestyle = itemToRemove.freestyle;

                        PlaylistItem added = multiplayerManager.addPlaylistItem(room.roomId, room.hostUserId, duplicate);
                        if (added != null) {
                            remainingActive.add(added);
                            broadcastToRoom(room, "PlaylistItemAdded", 1, packer -> packPlaylistItem(packer, added));
                        }
                    }

                    // If the item being removed is currently selected in room.settings.playlistItemId:
                    if (room.settings.playlistItemId == itemId && !remainingActive.isEmpty()) {
                        PlaylistItem newCurrent = remainingActive.get(0);
                        room.settings.playlistItemId = newCurrent.id;

                        // Broadcast SettingsChanged BEFORE PlaylistItemRemoved so client's CurrentPlaylistItem is always valid!
                        broadcastToRoom(room, "SettingsChanged", 1, packer -> packRoomSettings(packer, room.settings));

                        // Unready users
                        for (RoomUser u : room.users.values()) {
                            if (u.state == UserState.READY) {
                                u.state = UserState.IDLE;
                                broadcastToRoom(room, "UserStateChanged", 2, packer -> {
                                    packer.packInt(u.userId);
                                    packer.packInt(UserState.IDLE.value);
                                });
                            }
                        }
                    }

                    // Now safely remove the item from room playlist
                    boolean removed = multiplayerManager.removePlaylistItem(room.roomId, session.userId, itemId);
                    if (removed) {
                        if (invocationId != null) {
                            sendVoidCompletion(session, invocationId);
                        }
                        // Broadcast PlaylistItemRemoved now that Settings.PlaylistItemId points to a valid item
                        broadcastToRoom(room, "PlaylistItemRemoved", 1, packer -> packer.packLong(itemId));

                        updatePlaylistOrder(room);
                        return;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to remove playlist item: {}", e.getMessage());
                }
            }
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("StartMatch".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null) {
                multiplayerManager.startMatch(room.roomId, session.userId);
                if (invocationId != null) {
                    sendVoidCompletion(session, invocationId);
                }

                room.introSkipPassed = false;
                for (RoomUser u : room.users.values()) {
                    u.votedToSkipIntro = false;
                    if (u.state != UserState.SPECTATING) {
                        u.state = UserState.WAITING_FOR_LOAD;
                        broadcastToRoom(room, "UserStateChanged", 2, packer -> {
                            packer.packInt(u.userId);
                            packer.packInt(UserState.WAITING_FOR_LOAD.value);
                        });
                    }
                }

                broadcastToRoom(room, "RoomStateChanged", 1, packer -> packer.packInt(RoomState.WAITING_FOR_LOAD.value));
                broadcastToRoom(room, "LoadRequested", 0, null);
            } else if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("AbortMatch".equalsIgnoreCase(target) || "AbortGameplay".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null) {
                multiplayerManager.abortMatch(room.roomId);
                for (RoomUser u : room.users.values()) {
                    u.state = UserState.IDLE;
                    broadcastToRoom(room, "UserStateChanged", 2, packer -> {
                        packer.packInt(u.userId);
                        packer.packInt(UserState.IDLE.value);
                    });
                }
                room.state = RoomState.OPEN;
                broadcastToRoom(room, "RoomStateChanged", 1, packer -> packer.packInt(RoomState.OPEN.value));
                broadcastToRoom(room, "GameplayAborted", 1, packer -> packer.packInt(0));

                if (invocationId != null) {
                    sendVoidCompletion(session, invocationId);
                }
            } else if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("TransferHost".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null && argCount > 0 && unpacker.hasNext()) {
                int targetId = unpacker.unpackInt();
                multiplayerManager.transferHost(room.roomId, session.userId, targetId);
                if (invocationId != null) {
                    sendVoidCompletion(session, invocationId);
                }
                broadcastToRoom(room, "HostChanged", 1, packer -> packer.packInt(targetId));
            } else if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("KickUser".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null && argCount > 0 && unpacker.hasNext()) {
                int targetId = unpacker.unpackInt();
                RoomUser kicked = multiplayerManager.kickUser(room.roomId, session.userId, targetId);
                if (invocationId != null) {
                    sendVoidCompletion(session, invocationId);
                }
                if (kicked != null) {
                    broadcastToRoom(room, "UserKicked", 1, packer -> packRoomUser(packer, kicked));
                }
            } else if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("InvitePlayer".equalsIgnoreCase(target)) {
            Room room = multiplayerManager.getRoom(session.currentRoomId);
            if (room != null && argCount > 0 && unpacker.hasNext()) {
                int targetId = unpacker.unpackInt();
                ClientSession targetSession = userSessions.get(targetId);
                if (targetSession != null && targetSession.handshaken) {
                    sendInvocation(targetSession, "Invited", 3, packer -> {
                        packer.packInt(session.userId);
                        packer.packLong(room.roomId);
                        packer.packString(room.settings.password != null ? room.settings.password : "");
                    });
                }
            }
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        if ("SendMatchRequest".equalsIgnoreCase(target)) {
            if (invocationId != null) {
                sendVoidCompletion(session, invocationId);
            }
            return;
        }

        // Default void completion
        if (invocationId != null) {
            sendVoidCompletion(session, invocationId);
        }
    }

    private void finishCurrentPlaylistItem(Room room) {
        if (room == null) return;
        PlaylistItem current = room.getCurrentPlaylistItem();
        if (current == null) return;

        current.expired = true;
        current.playedAt = System.currentTimeMillis();
        broadcastToRoom(room, "PlaylistItemChanged", 1, packer -> packPlaylistItem(packer, current));

        updatePlaylistOrder(room);
        updateCurrentItem(room);
    }

    public void updatePlaylistOrder(Room room) {
        if (room == null || room.playlist.isEmpty()) return;

        List<PlaylistItem> activeItems = new ArrayList<>();
        for (PlaylistItem it : room.playlist) {
            if (!it.expired) {
                activeItems.add(it);
            }
        }

        if (room.settings.queueMode == 2) { // AllPlayersRoundRobin
            Map<Integer, List<PlaylistItem>> byOwner = new LinkedHashMap<>();
            for (PlaylistItem it : activeItems) {
                byOwner.computeIfAbsent(it.ownerId, k -> new ArrayList<>()).add(it);
            }
            List<PlaylistItem> roundRobin = new ArrayList<>();
            int maxPerUser = 0;
            for (List<PlaylistItem> list : byOwner.values()) {
                maxPerUser = Math.max(maxPerUser, list.size());
            }
            for (int i = 0; i < maxPerUser; i++) {
                for (List<PlaylistItem> list : byOwner.values()) {
                    if (i < list.size()) {
                        roundRobin.add(list.get(i));
                    }
                }
            }
            activeItems = roundRobin;
        } else {
            activeItems.sort(Comparator.comparingLong(a -> a.id));
        }

        for (int i = 0; i < activeItems.size(); i++) {
            PlaylistItem it = activeItems.get(i);
            if (it.playlistOrder != i) {
                it.playlistOrder = i;
                broadcastToRoom(room, "PlaylistItemChanged", 1, packer -> packPlaylistItem(packer, it));
            }
        }
    }

    public PlaylistItem updateCurrentItem(Room room) {
        if (room == null || room.playlist.isEmpty()) return null;

        PlaylistItem nextItem = null;
        for (PlaylistItem it : room.playlist) {
            if (!it.expired) {
                if (nextItem == null || it.playlistOrder < nextItem.playlistOrder) {
                    nextItem = it;
                }
            }
        }

        if (nextItem == null) {
            // All items expired: in HostOnly mode duplicate last played item
            PlaylistItem lastPlayed = room.playlist.get(room.playlist.size() - 1);
            PlaylistItem duplicate = new PlaylistItem();
            duplicate.beatmapId = lastPlayed.beatmapId;
            duplicate.beatmapChecksum = lastPlayed.beatmapChecksum;
            duplicate.rulesetId = lastPlayed.rulesetId;
            duplicate.requiredMods.addAll(lastPlayed.requiredMods);
            duplicate.allowedMods.addAll(lastPlayed.allowedMods);
            duplicate.starRating = lastPlayed.starRating;
            duplicate.freestyle = lastPlayed.freestyle;

            nextItem = multiplayerManager.addPlaylistItem(room.roomId, room.hostUserId, duplicate);
            if (nextItem != null) {
                PlaylistItem finalNext = nextItem;
                broadcastToRoom(room, "PlaylistItemAdded", 1, packer -> packPlaylistItem(packer, finalNext));
            }
        }

        if (nextItem != null && room.settings.playlistItemId != nextItem.id) {
            long oldId = room.settings.playlistItemId;
            room.settings.playlistItemId = nextItem.id;
            broadcastToRoom(room, "SettingsChanged", 1, packer -> packRoomSettings(packer, room.settings));

            if (oldId > 0 && oldId != nextItem.id) {
                for (RoomUser u : room.users.values()) {
                    if (u.state == UserState.READY) {
                        u.state = UserState.IDLE;
                        broadcastToRoom(room, "UserStateChanged", 2, packer -> {
                            packer.packInt(u.userId);
                            packer.packInt(UserState.IDLE.value);
                        });
                    }
                }
            }
        }

        return nextItem;
    }

    private void handleUserLeaveRoom(long roomId, int userId) {
        Room room = multiplayerManager.getRoom(roomId);
        if (room == null) return;

        RoomUser left = multiplayerManager.leaveRoom(roomId, userId);
        if (left != null) {
            broadcastToRoom(room, "UserLeft", 1, packer -> packRoomUser(packer, left));
            if (room.hostUserId > 0) {
                broadcastToRoom(room, "HostChanged", 1, packer -> packer.packInt(room.hostUserId));
            }
            if (room.state == RoomState.PLAYING) {
                checkVoteToSkipIntro(room);
            }
        }
    }

    private void checkVoteToSkipIntro(Room room) {
        if (room == null || room.introSkipPassed) return;
        if (room.settings != null && room.settings.autoSkip) {
            room.introSkipPassed = true;
            broadcastToRoom(room, "VoteToSkipIntroPassed", 0, null);
            return;
        }
        int countTotal = 0;
        int countSkipped = 0;
        for (RoomUser u : room.users.values()) {
            if (u.state == UserState.PLAYING || u.state == UserState.READY_FOR_GAMEPLAY || u.state == UserState.LOADED) {
                countTotal++;
                if (u.votedToSkipIntro) {
                    countSkipped++;
                }
            }
        }
        if (countTotal == 0) {
            for (RoomUser u : room.users.values()) {
                if (u.state != UserState.SPECTATING) {
                    countTotal++;
                    if (u.votedToSkipIntro) {
                        countSkipped++;
                    }
                }
            }
        }
        int countRequired = countTotal / 2 + 1;
        if (countTotal > 0 && countSkipped >= countRequired) {
            room.introSkipPassed = true;
            broadcastToRoom(room, "VoteToSkipIntroPassed", 0, null);
        }
    }

    private void broadcastToRoom(Room room, String target, int argCount, InvocationEncoder encoder) {
        broadcastToRoomExcept(room, -1, target, argCount, encoder);
    }

    private void broadcastToRoomExcept(Room room, int excludedUserId, String target, int argCount, InvocationEncoder encoder) {
        for (int uid : room.users.keySet()) {
            if (uid == excludedUserId) continue;
            ClientSession s = userSessions.get(uid);
            if (s != null && s.handshaken) {
                sendInvocation(s, target, argCount, encoder);
            }
        }
    }

    @FunctionalInterface
    public interface InvocationEncoder {
        void encode(MessagePacker packer) throws IOException;
    }

    public void sendInvocation(ClientSession session, String target, int argCount, InvocationEncoder encoder) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);

            packer.packArrayHeader(5);
            packer.packInt(1); // Invocation
            packer.packMapHeader(0); // Headers
            packer.packNil(); // invocationId
            packer.packString(target);
            packer.packArrayHeader(argCount);
            if (encoder != null) {
                encoder.encode(packer);
            }

            packer.close();
            byte[] msg = encodeLengthPrefixed(baos.toByteArray());
            session.ws.send(ByteBuffer.wrap(msg));
        } catch (Exception e) {
            logger.debug("Failed to send invocation {}: {}", target, e.getMessage());
        }
    }

    private void sendRoomCompletion(ClientSession session, String invocationId, Room room) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);

            // [3, {}, invocationId, 3, MultiplayerRoom]
            packer.packArrayHeader(5);
            packer.packInt(3); // Completion
            packer.packMapHeader(0);
            packer.packString(invocationId);
            packer.packInt(3); // ResultKind = NonVoid

            packMultiplayerRoom(packer, room);

            packer.close();
            byte[] msg = encodeLengthPrefixed(baos.toByteArray());
            session.ws.send(ByteBuffer.wrap(msg));
        } catch (Exception e) {
            logger.debug("Failed to send room completion: {}", e.getMessage());
        }
    }

    public static void packMultiplayerRoom(MessagePacker packer, Room room) throws IOException {
        packer.packArrayHeader(9);

        // Key 0: RoomID
        packer.packLong(room.roomId);

        // Key 1: State
        packer.packInt(room.state.value);

        // Key 2: Settings
        packRoomSettings(packer, room.settings);

        // Key 3: Users list
        packer.packArrayHeader(room.users.size());
        for (RoomUser u : room.users.values()) {
            packRoomUser(packer, u);
        }

        // Key 4: Host
        RoomUser host = room.users.get(room.hostUserId);
        if (host != null) {
            packRoomUser(packer, host);
        } else {
            packer.packNil();
        }

        // Key 5: MatchState
        packer.packNil();

        // Key 6: Playlist
        packer.packArrayHeader(room.playlist.size());
        for (PlaylistItem item : room.playlist) {
            packPlaylistItem(packer, item);
        }

        // Key 7: ActiveCountdowns
        packer.packArrayHeader(0);

        // Key 8: ChannelID
        packer.packInt(room.channelId);
    }

    public static void packRoomSettings(MessagePacker packer, RoomSettings s) throws IOException {
        packer.packArrayHeader(8);
        packer.packString(s.name != null ? s.name : "Multiplayer Room"); // 0: Name
        packer.packLong(s.playlistItemId); // 1: PlaylistItemId
        packer.packString(s.password != null ? s.password : ""); // 2: Password
        packer.packInt(s.matchType); // 3: MatchType
        packer.packInt(s.queueMode); // 4: QueueMode
        packer.packLong(s.autoStartDurationMs * 10000L); // 5: AutoStartDuration (ticks)
        packer.packBoolean(s.autoSkip); // 6: AutoSkip
        if (s.maxParticipants != null) packer.packInt(s.maxParticipants); // 7: MaxParticipants
        else packer.packNil();
    }

    public static RoomSettings parseRoomSettings(Value v) {
        if (v == null) return null;
        RoomSettings s = new RoomSettings();
        try {
            if (v.isArrayValue()) {
                ArrayValue arr = v.asArrayValue();
                if (arr.size() > 0 && !arr.get(0).isNilValue()) s.name = arr.get(0).asStringValue().asString();
                if (arr.size() > 1 && !arr.get(1).isNilValue()) s.playlistItemId = arr.get(1).asIntegerValue().asLong();
                if (arr.size() > 2 && !arr.get(2).isNilValue()) s.password = arr.get(2).asStringValue().asString();
                if (arr.size() > 3 && !arr.get(3).isNilValue()) s.matchType = arr.get(3).asIntegerValue().asInt();
                if (arr.size() > 4 && !arr.get(4).isNilValue()) s.queueMode = arr.get(4).asIntegerValue().asInt();
                if (arr.size() > 5 && !arr.get(5).isNilValue()) s.autoStartDurationMs = arr.get(5).asIntegerValue().asLong() / 10000L;
                if (arr.size() > 6 && !arr.get(6).isNilValue()) s.autoSkip = arr.get(6).asBooleanValue().getBoolean();
                if (arr.size() > 7 && !arr.get(7).isNilValue()) s.maxParticipants = arr.get(7).asIntegerValue().asInt();
            } else if (v.isMapValue()) {
                Map<Value, Value> map = v.asMapValue().map();
                for (Map.Entry<Value, Value> entry : map.entrySet()) {
                    String key = entry.getKey().asStringValue().asString().toLowerCase();
                    Value val = entry.getValue();
                    if (key.equals("name") && !val.isNilValue()) s.name = val.asStringValue().asString();
                    else if (key.equals("playlistitemid") && !val.isNilValue()) s.playlistItemId = val.asIntegerValue().asLong();
                    else if (key.equals("password") && !val.isNilValue()) s.password = val.asStringValue().asString();
                    else if (key.equals("matchtype") && !val.isNilValue()) s.matchType = val.asIntegerValue().asInt();
                    else if (key.equals("queuemode") && !val.isNilValue()) s.queueMode = val.asIntegerValue().asInt();
                    else if (key.equals("autostartduration") && !val.isNilValue()) s.autoStartDurationMs = val.asIntegerValue().asLong() / 10000L;
                    else if (key.equals("autoskip") && !val.isNilValue()) s.autoSkip = val.asBooleanValue().getBoolean();
                    else if (key.equals("maxparticipants") && !val.isNilValue()) s.maxParticipants = val.asIntegerValue().asInt();
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing RoomSettings: {}", e.getMessage());
        }
        return s;
    }

    public static void packRoomUser(MessagePacker packer, RoomUser u) throws IOException {
        packer.packArrayHeader(9);
        packer.packInt(u.userId); // 0: UserID
        packer.packInt(u.state.value); // 1: State

        // 2: BeatmapAvailability
        packer.packArrayHeader(2);
        packer.packInt(u.beatmapAvailability.state);
        if (u.beatmapAvailability.downloadProgress != null) packer.packDouble(u.beatmapAvailability.downloadProgress);
        else packer.packNil();

        // 3: Mods
        packMods(packer, u.mods);

        // 4: MatchState
        packer.packNil();

        // 5: RulesetId
        if (u.rulesetId != null) packer.packInt(u.rulesetId);
        else packer.packNil();

        // 6: BeatmapId
        if (u.beatmapId != null) packer.packInt(u.beatmapId);
        else packer.packNil();

        // 7: VotedToSkipIntro
        packer.packBoolean(u.votedToSkipIntro);

        // 8: Role
        packer.packInt(u.role);
    }

    public static void packPlaylistItem(MessagePacker packer, PlaylistItem item) throws IOException {
        packer.packArrayHeader(12);
        packer.packLong(item.id); // 0: ID
        packer.packInt(item.ownerId); // 1: OwnerID
        packer.packInt(item.beatmapId); // 2: BeatmapID
        packer.packString(item.beatmapChecksum != null ? item.beatmapChecksum : ""); // 3: BeatmapChecksum
        packer.packInt(item.rulesetId); // 4: RulesetID
        
        // 5: RequiredMods
        if (item.requiredMods != null && !item.requiredMods.isEmpty()) {
            packer.packArrayHeader(item.requiredMods.size());
            for (Map<String, Object> mod : item.requiredMods) {
                packAPIMod(packer, mod);
            }
        } else {
            packer.packArrayHeader(0);
        }

        // 6: AllowedMods
        if (item.allowedMods != null && !item.allowedMods.isEmpty()) {
            packer.packArrayHeader(item.allowedMods.size());
            for (Map<String, Object> mod : item.allowedMods) {
                packAPIMod(packer, mod);
            }
        } else {
            packer.packArrayHeader(0);
        }

        packer.packBoolean(item.expired); // 7: Expired
        packer.packInt(item.playlistOrder); // 8: PlaylistOrder
        packer.packNil(); // 9: PlayedAt
        packer.packDouble(item.starRating); // 10: StarRating
        packer.packBoolean(item.freestyle); // 11: Freestyle
    }

    public static void packBeatmapAvailability(MessagePacker packer, BeatmapAvailability ba) throws IOException {
        packer.packArrayHeader(2);
        packer.packInt(ba != null ? ba.state : 4);
        if (ba != null && ba.downloadProgress != null) {
            packer.packDouble(ba.downloadProgress);
        } else {
            packer.packNil();
        }
    }

    public static void packMods(MessagePacker packer, List<Map<String, Object>> mods) throws IOException {
        if (mods == null || mods.isEmpty()) {
            packer.packArrayHeader(0);
            return;
        }
        packer.packArrayHeader(mods.size());
        for (Map<String, Object> mod : mods) {
            packAPIMod(packer, mod);
        }
    }

    public static void packAPIMod(MessagePacker packer, Map<String, Object> mod) throws IOException {
        packer.packArrayHeader(2);
        String acronym = "";
        if (mod != null && mod.containsKey("acronym")) {
            Object a = mod.get("acronym");
            if (a != null) acronym = a.toString();
        }
        packer.packString(acronym);
        packer.packArrayHeader(0);
    }

    public static PlaylistItem parsePlaylistItem(Value v) {
        if (v == null) return null;
        PlaylistItem item = new PlaylistItem();
        try {
            if (v.isArrayValue()) {
                ArrayValue arr = v.asArrayValue();
                if (arr.size() > 0 && !arr.get(0).isNilValue()) item.id = arr.get(0).asIntegerValue().asLong();
                if (arr.size() > 1 && !arr.get(1).isNilValue()) item.ownerId = arr.get(1).asIntegerValue().asInt();
                if (arr.size() > 2 && !arr.get(2).isNilValue()) item.beatmapId = arr.get(2).asIntegerValue().asInt();
                if (arr.size() > 3 && !arr.get(3).isNilValue()) item.beatmapChecksum = arr.get(3).asStringValue().asString();
                if (arr.size() > 4 && !arr.get(4).isNilValue()) item.rulesetId = arr.get(4).asIntegerValue().asInt();
                if (arr.size() > 5 && !arr.get(5).isNilValue()) item.requiredMods.addAll(parseMods(arr.get(5)));
                if (arr.size() > 6 && !arr.get(6).isNilValue()) item.allowedMods.addAll(parseMods(arr.get(6)));
                if (arr.size() > 7 && !arr.get(7).isNilValue()) item.expired = arr.get(7).asBooleanValue().getBoolean();
                if (arr.size() > 8 && !arr.get(8).isNilValue()) item.playlistOrder = arr.get(8).asIntegerValue().asInt();
                if (arr.size() > 10 && !arr.get(10).isNilValue()) {
                    if (arr.get(10).isFloatValue()) item.starRating = arr.get(10).asFloatValue().toDouble();
                    else if (arr.get(10).isIntegerValue()) item.starRating = (double) arr.get(10).asIntegerValue().asLong();
                }
                if (arr.size() > 11 && !arr.get(11).isNilValue()) item.freestyle = arr.get(11).asBooleanValue().getBoolean();
            } else if (v.isMapValue()) {
                Map<Value, Value> map = v.asMapValue().map();
                for (Map.Entry<Value, Value> entry : map.entrySet()) {
                    String key = entry.getKey().asStringValue().asString().toLowerCase();
                    Value val = entry.getValue();
                    if (key.equals("id") && !val.isNilValue()) item.id = val.asIntegerValue().asLong();
                    else if (key.equals("ownerid") && !val.isNilValue()) item.ownerId = val.asIntegerValue().asInt();
                    else if (key.equals("beatmapid") && !val.isNilValue()) item.beatmapId = val.asIntegerValue().asInt();
                    else if (key.equals("beatmapchecksum") && !val.isNilValue()) item.beatmapChecksum = val.asStringValue().asString();
                    else if (key.equals("rulesetid") && !val.isNilValue()) item.rulesetId = val.asIntegerValue().asInt();
                    else if (key.equals("requiredmods") && !val.isNilValue()) item.requiredMods.addAll(parseMods(val));
                    else if (key.equals("allowedmods") && !val.isNilValue()) item.allowedMods.addAll(parseMods(val));
                    else if (key.equals("expired") && !val.isNilValue()) item.expired = val.asBooleanValue().getBoolean();
                    else if (key.equals("playlistorder") && !val.isNilValue()) item.playlistOrder = val.asIntegerValue().asInt();
                    else if (key.equals("starrating") && !val.isNilValue()) {
                        if (val.isFloatValue()) item.starRating = val.asFloatValue().toDouble();
                        else if (val.isIntegerValue()) item.starRating = (double) val.asIntegerValue().asLong();
                    }
                    else if (key.equals("freestyle") && !val.isNilValue()) item.freestyle = val.asBooleanValue().getBoolean();
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing PlaylistItem: {}", e.getMessage());
        }
        return item;
    }

    public static List<Map<String, Object>> parseMods(Value v) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (v == null || !v.isArrayValue()) return list;
        ArrayValue arr = v.asArrayValue();
        for (Value mv : arr) {
            if (mv.isArrayValue()) {
                ArrayValue mArr = mv.asArrayValue();
                if (mArr.size() > 0 && !mArr.get(0).isNilValue()) {
                    String acronym = mArr.get(0).asStringValue().asString();
                    list.add(Map.of("acronym", acronym));
                }
            } else if (mv.isMapValue()) {
                Map<Value, Value> map = mv.asMapValue().map();
                for (Map.Entry<Value, Value> e : map.entrySet()) {
                    if (e.getKey().asStringValue().asString().equalsIgnoreCase("acronym") && !e.getValue().isNilValue()) {
                        list.add(Map.of("acronym", e.getValue().asStringValue().asString()));
                        break;
                    }
                }
            }
        }
        return list;
    }

    private void sendVoidCompletion(ClientSession session, String invocationId) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);

            packer.packArrayHeader(4);
            packer.packInt(3); // Completion
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

    private void sendErrorCompletion(ClientSession session, String invocationId, String error) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessagePacker packer = MessagePack.newDefaultPacker(baos);

            packer.packArrayHeader(5);
            packer.packInt(3); // Completion
            packer.packMapHeader(0);
            packer.packString(invocationId);
            packer.packInt(1); // Error
            packer.packString(error);

            packer.close();
            byte[] msg = encodeLengthPrefixed(baos.toByteArray());
            session.ws.send(ByteBuffer.wrap(msg));
        } catch (Exception e) {
            logger.debug("Failed to send error completion: {}", e.getMessage());
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

    private void broadcastPing() {
        for (ClientSession s : sessions.values()) {
            if (s.handshaken) {
                sendPing(s);
            }
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
