package com.osuserverlist.lazer.signalr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.online.OnlineManager;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NotificationHub {
    private static final Logger logger = LoggerFactory.getLogger(NotificationHub.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final OnlineManager onlineManager;

    private final Map<String, NotificationSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, Set<NotificationSession>> userSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static class NotificationSession {
        public final WsContext ws;
        public final int userId;
        public boolean listeningChat = true;

        public NotificationSession(WsContext ws, int userId) {
            this.ws = ws;
            this.userId = userId;
        }
    }

    public NotificationHub(AuthService authService, DatabaseManager databaseManager, OnlineManager onlineManager) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.onlineManager = onlineManager;

        // Periodic keepalive ping to notification websocket clients
        this.scheduler.scheduleAtFixedRate(this::broadcastPing, 10, 10, TimeUnit.SECONDS);
    }

    public void configureWs(WsConfig ws) {
        ws.onConnect(this::onConnect);
        ws.onMessage(this::onTextMessage);
        ws.onClose(this::onClose);
        ws.onError(ctx -> logger.debug("NotificationHub WebSocket error: {}", ctx.error() != null ? ctx.error().getMessage() : "unknown"));
    }

    private void onConnect(WsConnectContext ctx) {
        int userId = resolveUserId(ctx);
        NotificationSession session = new NotificationSession(ctx, userId);
        sessions.put(ctx.sessionId(), session);

        if (userId > 0) {
            userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
            onlineManager.markUserActive(userId);
            logger.info("User {} connected to NotificationHub [{}]", userId, ctx.sessionId());
        }
    }

    private void onClose(WsCloseContext ctx) {
        NotificationSession session = sessions.remove(ctx.sessionId());
        if (session != null && session.userId > 0) {
            Set<NotificationSession> set = userSessions.get(session.userId);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    userSessions.remove(session.userId);
                }
            }
            logger.info("User {} disconnected from NotificationHub [{}]", session.userId, ctx.sessionId());
        }
    }

    private void onTextMessage(WsMessageContext ctx) {
        NotificationSession session = sessions.get(ctx.sessionId());
        if (session == null) return;

        String msg = ctx.message();
        if (msg == null || msg.isBlank()) return;

        try {
            JsonNode node = mapper.readTree(msg);
            if (node != null && node.has("event")) {
                String event = node.get("event").asText();
                if ("chat.start".equalsIgnoreCase(event)) {
                    session.listeningChat = true;
                    // Send default #osu channel join event
                    Map<String, Object> defaultChannel = new LinkedHashMap<>();
                    defaultChannel.put("channel_id", 1);
                    defaultChannel.put("name", "#osu");
                    defaultChannel.put("description", "General chat");
                    defaultChannel.put("type", "PUBLIC");
                    defaultChannel.put("moderated", false);
                    defaultChannel.put("joined", true);

                    sendEvent(session, "chat.channel.join", defaultChannel);
                    logger.info("User {} started listening to chat notifications", session.userId);
                } else if ("chat.end".equalsIgnoreCase(event)) {
                    session.listeningChat = false;
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse notification client message: {}", e.getMessage());
        }
    }

    public void broadcastChatMessage(Map<String, Object> messageMap) {
        if (messageMap == null) return;
        Map<String, Object> data = Map.of("messages", List.of(messageMap));

        for (NotificationSession s : sessions.values()) {
            if (s.listeningChat) {
                sendEvent(s, "chat.message.new", data);
            }
        }
    }

    public void notifyChannelJoin(int userId, Map<String, Object> channelMap) {
        if (userId <= 0 || channelMap == null) return;
        Set<NotificationSession> set = userSessions.get(userId);
        if (set != null) {
            for (NotificationSession s : set) {
                sendEvent(s, "chat.channel.join", channelMap);
            }
        }
    }

    public void notifyChannelPart(int userId, Map<String, Object> channelMap) {
        if (userId <= 0 || channelMap == null) return;
        Set<NotificationSession> set = userSessions.get(userId);
        if (set != null) {
            for (NotificationSession s : set) {
                sendEvent(s, "chat.channel.part", channelMap);
            }
        }
    }

    private void sendEvent(NotificationSession session, String event, Object data) {
        try {
            Map<String, Object> packet = new LinkedHashMap<>();
            packet.put("event", event);
            packet.put("data", data);
            String json = mapper.writeValueAsString(packet);
            session.ws.send(json);
        } catch (Exception e) {
            logger.debug("Failed to send notification event {}: {}", event, e.getMessage());
        }
    }

    private void broadcastPing() {
        for (NotificationSession s : sessions.values()) {
            try {
                s.ws.send("{\"event\":\"ping\"}");
            } catch (Exception ignored) {}
        }
    }

    private int resolveUserId(WsContext ctx) {
        String token = ctx.queryParam("access_token");
        if (token == null || token.isBlank()) {
            token = ctx.queryParam("token");
        }
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
}
