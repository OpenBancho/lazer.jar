package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import com.osuserverlist.lazer.models.UserStatistics;
import com.osuserverlist.lazer.online.OnlineManager;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class ChatHandler implements Handler {
    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final OnlineManager onlineManager;
    private final ServerConfig config;
    private final com.osuserverlist.lazer.signalr.NotificationHub notificationHub;

    private final AtomicLong nextMessageId = new AtomicLong(System.currentTimeMillis());
    private final Map<Integer, List<Map<String, Object>>> channelMessages = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> allMessages = new CopyOnWriteArrayList<>();
    private final Map<Integer, Set<Integer>> userJoinedChannels = new ConcurrentHashMap<>();

    public ChatHandler(AuthService authService, DatabaseManager databaseManager, OnlineManager onlineManager, ServerConfig config,
                       com.osuserverlist.lazer.signalr.NotificationHub notificationHub) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.onlineManager = onlineManager;
        this.config = config;
        this.notificationHub = notificationHub;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String path = ctx.path();

        if (path.endsWith("/ack")) {
            handleAck(ctx);
            return;
        }

        if (path.endsWith("/channels") || path.endsWith("/channels/")) {
            handleGetChannels(ctx);
            return;
        }

        if (path.endsWith("/updates")) {
            handleGetUpdates(ctx);
            return;
        }

        ctx.status(200).json(Collections.emptyMap());
    }

    public void handleAck(@NotNull Context ctx) {
        ctx.status(200).json(Map.of("silences", Collections.emptyList()));
    }

    public void handleGetChannels(@NotNull Context ctx) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(formatChannel(1, "#osu", "General chat", "PUBLIC", true));
        list.add(formatChannel(2, "#lobby", "Multiplayer lobby chat", "PUBLIC", false));

        int userId = resolveUserId(ctx);
        if (userId > 0) {
            Set<Integer> joined = userJoinedChannels.get(userId);
            if (joined != null) {
                for (int chId : joined) {
                    if (chId != 1 && chId != 2) {
                        list.add(formatChannel(chId, chId > 1000 ? "#multiplayer" : "#channel_" + chId,
                                chId > 1000 ? "Multiplayer Room" : "Chat",
                                chId > 1000 ? "MULTIPLAYER" : "PUBLIC", true));
                    }
                }
            }
        }

        ctx.status(200).json(list);
    }

    public void handleGetChannel(@NotNull Context ctx) {
        int channelId = parseChannelId(ctx.pathParam("channel_id"));
        int userId = resolveUserId(ctx);
        boolean joined = (channelId == 1);
        if (userId > 0) {
            Set<Integer> set = userJoinedChannels.get(userId);
            if (set != null && set.contains(channelId)) {
                joined = true;
            }
        }

        Map<String, Object> channel = formatChannel(channelId,
                channelId > 1000 ? "#multiplayer" : (channelId == 1 ? "#osu" : (channelId == 2 ? "#lobby" : "#channel_" + channelId)),
                channelId > 1000 ? "Multiplayer Room" : "Chat",
                channelId > 1000 ? "MULTIPLAYER" : "PUBLIC",
                joined);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("channel", channel);
        resp.put("users", Collections.emptyList());

        ctx.status(200).json(resp);
    }

    public void handleGetChannelMessages(@NotNull Context ctx) {
        int channelId = parseChannelId(ctx.pathParam("channel_id"));
        long since = 0;
        String sinceParam = ctx.queryParam("since");
        if (sinceParam != null) {
            try { since = Long.parseLong(sinceParam); } catch (NumberFormatException ignored) {}
        }
        int limit = 50;
        String limitParam = ctx.queryParam("limit");
        if (limitParam != null) {
            try { limit = Math.min(Math.max(Integer.parseInt(limitParam), 1), 100); } catch (NumberFormatException ignored) {}
        }

        List<Map<String, Object>> msgs = channelMessages.getOrDefault(channelId, Collections.emptyList());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> m : msgs) {
            long mId = (m.get("message_id") instanceof Number n) ? n.longValue() : 0;
            if (since > 0 && mId <= since) continue;
            result.add(m);
            if (result.size() >= limit) break;
        }

        ctx.status(200).json(result);
    }

    public void handlePostChannelMessage(@NotNull Context ctx) {
        int channelId = parseChannelId(ctx.pathParam("channel_id"));
        int userId = resolveUserId(ctx);

        User user = (userId > 0) ? databaseManager.findUserById(userId) : null;
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        String content = ctx.formParam("message");
        String uuid = ctx.formParam("uuid");
        String isActionStr = ctx.formParam("is_action");
        boolean isAction = Boolean.parseBoolean(isActionStr);

        if (content == null || content.isBlank()) {
            try {
                JsonNode body = mapper.readTree(ctx.body());
                if (body != null) {
                    if (body.has("message")) content = body.get("message").asText();
                    if (body.has("content")) content = body.get("content").asText();
                    if (body.has("uuid")) uuid = body.get("uuid").asText();
                    if (body.has("is_action")) isAction = body.get("is_action").asBoolean();
                }
            } catch (Exception ignored) {}
        }

        if (content == null) content = "";
        if (uuid == null || uuid.isBlank()) uuid = UUID.randomUUID().toString();

        long messageId = nextMessageId.getAndIncrement();
        String timestamp = ISO_FORMATTER.format(Instant.now());

        Map<String, Object> senderObj = buildSenderObject(user);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("message_id", messageId);
        message.put("channel_id", channelId);
        message.put("is_action", isAction);
        message.put("timestamp", timestamp);
        message.put("content", content);
        message.put("sender_id", user.id);
        message.put("uuid", uuid);
        message.put("sender", senderObj);

        channelMessages.computeIfAbsent(channelId, k -> new CopyOnWriteArrayList<>()).add(message);
        allMessages.add(message);
        if (allMessages.size() > 5000) {
            allMessages.remove(0);
        }

        // Auto join channel for sender
        userJoinedChannels.computeIfAbsent(user.id, k -> ConcurrentHashMap.newKeySet()).add(channelId);

        if (notificationHub != null) {
            notificationHub.broadcastChatMessage(message);
        }

        logger.info("Chat message [{}] sent by user {} ({}) in channel {}: {}",
                messageId, user.id, user.name, channelId, content);

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(message);
    }

    public void handleGetUpdates(@NotNull Context ctx) {
        int userId = resolveUserId(ctx);
        long since = 0;
        String sinceParam = ctx.queryParam("since");
        if (sinceParam != null) {
            try { since = Long.parseLong(sinceParam); } catch (NumberFormatException ignored) {}
        }

        List<Map<String, Object>> newMsgs = new ArrayList<>();
        Set<Integer> joined = (userId > 0) ? userJoinedChannels.getOrDefault(userId, Set.of(1)) : Set.of(1);

        for (Map<String, Object> msg : allMessages) {
            long mId = (msg.get("message_id") instanceof Number n) ? n.longValue() : 0;
            if (mId > since) {
                int chId = (msg.get("channel_id") instanceof Number n) ? n.intValue() : 0;
                if (chId == 1 || chId > 1000 || joined.contains(chId)) {
                    newMsgs.add(msg);
                }
            }
        }

        List<Map<String, Object>> presence = new ArrayList<>();
        presence.add(formatChannel(1, "#osu", "General chat", "PUBLIC", true));
        if (userId > 0 && userJoinedChannels.containsKey(userId)) {
            for (int chId : userJoinedChannels.get(userId)) {
                if (chId != 1) {
                    presence.add(formatChannel(chId, chId > 1000 ? "#multiplayer" : "#channel_" + chId,
                            chId > 1000 ? "Multiplayer Room" : "Chat",
                            chId > 1000 ? "MULTIPLAYER" : "PUBLIC", true));
                }
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("messages", newMsgs);
        resp.put("presence", presence);
        resp.put("silences", Collections.emptyList());

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }

    public void handleJoinChannel(@NotNull Context ctx) {
        int channelId = parseChannelId(ctx.pathParam("channel_id"));
        int userId = resolveUserId(ctx);
        if (userId > 0) {
            userJoinedChannels.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(channelId);
        }
        Map<String, Object> ch = formatChannel(channelId, channelId > 1000 ? "#multiplayer" : "#osu", "Chat", channelId > 1000 ? "MULTIPLAYER" : "PUBLIC", true);
        if (notificationHub != null && userId > 0) {
            notificationHub.notifyChannelJoin(userId, ch);
        }
        ctx.status(200).json(ch);
    }

    public void handleLeaveChannel(@NotNull Context ctx) {
        int channelId = parseChannelId(ctx.pathParam("channel_id"));
        int userId = resolveUserId(ctx);
        if (userId > 0) {
            Set<Integer> set = userJoinedChannels.get(userId);
            if (set != null) set.remove(channelId);
        }
        Map<String, Object> ch = formatChannel(channelId, channelId > 1000 ? "#multiplayer" : "#osu", "Chat", channelId > 1000 ? "MULTIPLAYER" : "PUBLIC", false);
        if (notificationHub != null && userId > 0) {
            notificationHub.notifyChannelPart(userId, ch);
        }
        ctx.status(200).json(Map.of("success", true));
    }

    public void handleMarkAsRead(@NotNull Context ctx) {
        ctx.status(200).json(Map.of("success", true));
    }

    private Map<String, Object> formatChannel(int channelId, String name, String desc, String type, boolean joined) {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("channel_id", channelId);
        channel.put("name", name);
        channel.put("description", desc);
        channel.put("type", type);
        channel.put("moderated", false);
        channel.put("joined", joined);
        return channel;
    }

    private Map<String, Object> buildSenderObject(User user) {
        Map<String, Object> senderObj = new LinkedHashMap<>();
        senderObj.put("id", user.id);
        senderObj.put("username", user.name);
        String countryCode = (user.country != null && !user.country.isBlank()) ? user.country.toUpperCase() : "XX";
        senderObj.put("country_code", countryCode);

        Map<String, Object> country = new LinkedHashMap<>();
        country.put("code", countryCode);
        country.put("name", countryCode);
        senderObj.put("country", country);

        String avatarUrl = UserResponseBuilder.getAvatarUrl(user.id, config);
        senderObj.put("avatar_url", avatarUrl);
        senderObj.put("custom_avatar_url", avatarUrl);

        String coverUrl = UserResponseBuilder.getCoverUrl(user.customBanner, config);
        Map<String, Object> cover = new LinkedHashMap<>();
        cover.put("custom_url", coverUrl);
        cover.put("url", coverUrl);
        cover.put("id", null);
        senderObj.put("cover", cover);
        senderObj.put("cover_url", coverUrl);

        senderObj.put("is_active", true);
        senderObj.put("is_bot", false);
        senderObj.put("is_deleted", false);
        senderObj.put("is_online", true);
        senderObj.put("is_supporter", user.donorEnd > (System.currentTimeMillis() / 1000));
        senderObj.put("profile_colour", null);
        return senderObj;
    }

    private int resolveUserId(Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            TokenStore.TokenData tokenData = authService.resolveToken(authHeader.substring(7).trim());
            if (tokenData != null) {
                return tokenData.userId;
            }
        }
        return -1;
    }

    private static int parseChannelId(String param) {
        if (param == null) return 1;
        try {
            return Integer.parseInt(param.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
