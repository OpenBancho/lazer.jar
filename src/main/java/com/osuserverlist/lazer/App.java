package com.osuserverlist.lazer;

import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.handlers.*;
import com.osuserverlist.lazer.online.OnlineManager;
import io.github.cdimascio.dotenv.Dotenv;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        System.out.println("""
                 __                                  _            \s
                |  |   ___  ___  ___  ___        _|_|___  ___    \s
                |  |__| .'||_ -|| -_||  _|   _  | | | .'||  _|   \s
                |_____|__,||___||___||_|    |_| |_|_|__,||_|     \s
                osu!(lazer) Server backend for bancho.jar ecosystem
                """);

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        ServerConfig config = new ServerConfig(dotenv);

        DatabaseManager databaseManager = new DatabaseManager();
        databaseManager.init(config);

        TokenStore tokenStore = new TokenStore();
        tokenStore.init(config);

        OnlineManager onlineManager = new OnlineManager(tokenStore, databaseManager);

        AuthService authService = new AuthService(databaseManager, tokenStore);

        com.osuserverlist.lazer.telemetry.TelemetryManager telemetryManager = new com.osuserverlist.lazer.telemetry.TelemetryManager(config, databaseManager);
        OAuthHandler oauthHandler = new OAuthHandler(authService, telemetryManager);
        MeHandler meHandler = new MeHandler(authService, databaseManager, config);
        UserHandler userHandler = new UserHandler(databaseManager, onlineManager, config);
        UserScoresHandler userScoresHandler = new UserScoresHandler(databaseManager, config);
        RelationshipsHandler relationshipsHandler = new RelationshipsHandler(authService, databaseManager, onlineManager, config);
        com.osuserverlist.lazer.signalr.NotificationHub notificationHub = new com.osuserverlist.lazer.signalr.NotificationHub(authService, databaseManager, onlineManager);
        NotificationsHandler notificationsHandler = new NotificationsHandler(config);
        MiscHandler miscHandler = new MiscHandler();
        com.osuserverlist.lazer.signalr.SpectatorHub spectatorHub = new com.osuserverlist.lazer.signalr.SpectatorHub(authService, databaseManager, onlineManager);
        ChatHandler chatHandler = new ChatHandler(authService, databaseManager, onlineManager, config, notificationHub);
        SoloScoreHandler soloScoreHandler = new SoloScoreHandler(authService, databaseManager, config, spectatorHub);
        BeatmapHandler beatmapHandler = new BeatmapHandler(databaseManager, config);
        RankingsHandler rankingsHandler = new RankingsHandler(databaseManager, config);
        SearchHandler searchHandler = new SearchHandler(databaseManager, onlineManager, config);
        com.osuserverlist.lazer.multiplayer.MultiplayerManager multiplayerManager = new com.osuserverlist.lazer.multiplayer.MultiplayerManager(databaseManager);
        RoomHandler roomHandler = new RoomHandler(authService, databaseManager, multiplayerManager, onlineManager, config, spectatorHub);
        com.osuserverlist.lazer.signalr.MetadataHub metadataHub = new com.osuserverlist.lazer.signalr.MetadataHub(authService, databaseManager, onlineManager);
        com.osuserverlist.lazer.signalr.MultiplayerHub multiplayerHub = new com.osuserverlist.lazer.signalr.MultiplayerHub(authService, databaseManager, multiplayerManager, onlineManager);

        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.jetty.threadPool = new org.eclipse.jetty.util.thread.QueuedThreadPool(250, 10, 60000);
            javalinConfig.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost();
                });
            });
            javalinConfig.requestLogger.http((ctx, ms) -> {
                logger.info("{} {} -> {} ({} ms)", ctx.method(), ctx.path(), ctx.status(), ms);
            });

            // Activity tracker for authenticated requests
            javalinConfig.routes.before(ctx -> {
                String authHeader = ctx.header("Authorization");
                if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    TokenStore.TokenData tokenData = authService.resolveToken(authHeader.substring(7).trim());
                    if (tokenData != null && tokenData.userId > 0) {
                        onlineManager.markUserActive(tokenData.userId);
                    }
                }
            });

            // Health & Info
            javalinConfig.routes.get("/", ctx -> {
                ctx.contentType("application/json");
                ctx.json(Map.of(
                        "server", config.serverName,
                        "status", "online",
                        "version", config.serverVersion,
                        "online_players", onlineManager.getCombinedOnlineCount()
                ));
            });

            javalinConfig.routes.get("/api/v2", ctx -> {
                ctx.contentType("application/json");
                ctx.json(Map.of(
                        "api_version", 2,
                        "status", "ok"
                ));
            });

            // OAuth2 token endpoints
            javalinConfig.routes.post("/oauth/token", oauthHandler);
            javalinConfig.routes.post("/api/v2/oauth/token", oauthHandler);

            // API v2 Me endpoints
            javalinConfig.routes.get("/api/v2/me", meHandler);
            javalinConfig.routes.get("/api/v2/me/", meHandler);
            javalinConfig.routes.get("/api/v2/me/{ruleset}", meHandler);
            javalinConfig.routes.get("/api/v2/me/beatmapset-favourites", miscHandler);

            // Dashboard & User Search & Batch Lookup
            javalinConfig.routes.get("/api/v2/search", searchHandler);
            javalinConfig.routes.get("/api/v2/search/", searchHandler);
            javalinConfig.routes.get("/api/v2/users/search", searchHandler);
            javalinConfig.routes.get("/api/v2/users/search/", searchHandler);
            javalinConfig.routes.get("/api/v2/users", userHandler::handleLookup);
            javalinConfig.routes.get("/api/v2/users/", userHandler::handleLookup);
            javalinConfig.routes.get("/api/v2/users/lookup", userHandler::handleLookup);
            javalinConfig.routes.get("/api/v2/users/lookup/", userHandler::handleLookup);

            // Users info & stats
            javalinConfig.routes.get("/api/v2/users/{user_id}", userHandler);
            javalinConfig.routes.get("/api/v2/users/{user_id}/", userHandler);
            javalinConfig.routes.get("/api/v2/users/{user_id}/{ruleset}", userHandler);
            javalinConfig.routes.get("/api/v2/users/{user_id}/recent_activity", ctx -> ctx.json(java.util.Collections.emptyList()));
            javalinConfig.routes.get("/api/v2/users/{user_id}/scores/{type}", userScoresHandler);
            javalinConfig.routes.get("/api/v2/users/{user_id}/beatmapsets/{type}", ctx -> ctx.json(java.util.Collections.emptyList()));
            javalinConfig.routes.get("/api/v2/users/{user_id}/kudosu", ctx -> ctx.json(java.util.Collections.emptyList()));

            // Rankings & Leaderboards
            javalinConfig.routes.get("/api/v2/rankings/{ruleset}/{type}", rankingsHandler::handleGetRankings);
            javalinConfig.routes.get("/api/v2/rankings/{ruleset}/country", rankingsHandler::handleGetCountryRankings);
            javalinConfig.routes.get("/api/v2/rankings/kudosu", rankingsHandler::handleGetKudosuRankings);
            javalinConfig.routes.get("/api/v2/rankings/{ruleset}/spotlight", rankingsHandler::handleGetRankings);
            javalinConfig.routes.get("/api/v2/rankings/{ruleset}/spotlights", rankingsHandler::handleGetRankings);
            javalinConfig.routes.get("/api/v2/spotlights", rankingsHandler::handleGetSpotlights);

            // Beatmaps, Search & Downloads
            javalinConfig.routes.get("/api/v2/beatmapsets/search", beatmapHandler::handleSearchBeatmapsets);
            javalinConfig.routes.get("/api/v2/beatmapsets/{beatmapset_id}/download", beatmapHandler::handleDownloadBeatmapset);
            javalinConfig.routes.get("/d/{id}", beatmapHandler::handleLegacyDownload);
            javalinConfig.routes.get("/web/osu-search.php", beatmapHandler::handleLegacySearch);
            javalinConfig.routes.get("/web/osu-search-set.php", beatmapHandler::handleLegacySearchSet);
            javalinConfig.routes.post("/api/v2/beatmaps/{beatmap_id}/solo/scores", soloScoreHandler::handleCreateScoreToken);
            javalinConfig.routes.put("/api/v2/beatmaps/{beatmap_id}/solo/scores/{token}", soloScoreHandler::handleSubmitScore);
            javalinConfig.routes.post("/api/v2/beatmaps/{beatmap_id}/solo/scores/{token}", soloScoreHandler::handleSubmitScore);
            javalinConfig.routes.get("/api/v2/beatmaps/{beatmap_id}/scores", soloScoreHandler::handleGetBeatmapScores);
            javalinConfig.routes.get("/api/v2/beatmapsets/{beatmapset_id}", beatmapHandler::handleGetBeatmapset);
            javalinConfig.routes.get("/api/v2/beatmapsets/lookup", beatmapHandler::handleLookupBeatmapset);
            javalinConfig.routes.get("/api/v2/beatmaps", beatmapHandler::handleGetBeatmaps);
            javalinConfig.routes.get("/api/v2/beatmaps/", beatmapHandler::handleGetBeatmaps);
            javalinConfig.routes.get("/api/v2/beatmaps/{beatmap_id}", beatmapHandler::handleGetBeatmap);
            javalinConfig.routes.get("/api/v2/beatmaps/lookup", beatmapHandler::handleLookupBeatmap);

            // Friends & Blocks
            javalinConfig.routes.get("/api/v2/friends", relationshipsHandler);
            javalinConfig.routes.get("/api/v2/blocks", relationshipsHandler);

            // Notifications
            javalinConfig.routes.get("/api/v2/notifications", notificationsHandler);
            javalinConfig.routes.post("/api/v2/notifications/mark-read", notificationsHandler);

            // Chat
            javalinConfig.routes.post("/api/v2/chat/ack", chatHandler::handleAck);
            javalinConfig.routes.get("/api/v2/chat/channels", chatHandler::handleGetChannels);
            javalinConfig.routes.get("/api/v2/chat/channels/", chatHandler::handleGetChannels);
            javalinConfig.routes.get("/api/v2/chat/channels/{channel_id}", chatHandler::handleGetChannel);
            javalinConfig.routes.get("/api/v2/chat/channels/{channel_id}/messages", chatHandler::handleGetChannelMessages);
            javalinConfig.routes.post("/api/v2/chat/channels/{channel_id}/messages", chatHandler::handlePostChannelMessage);
            javalinConfig.routes.put("/api/v2/chat/channels/{channel_id}/users/{user_id}", chatHandler::handleJoinChannel);
            javalinConfig.routes.delete("/api/v2/chat/channels/{channel_id}/users/{user_id}", chatHandler::handleLeaveChannel);
            javalinConfig.routes.put("/api/v2/chat/channels/{channel_id}/mark-as-read/{message_id}", chatHandler::handleMarkAsRead);
            javalinConfig.routes.get("/api/v2/chat/updates", chatHandler::handleGetUpdates);

            // Legacy non-v2 chat paths
            javalinConfig.routes.post("/chat/ack", chatHandler::handleAck);
            javalinConfig.routes.get("/chat/channels", chatHandler::handleGetChannels);
            javalinConfig.routes.get("/chat/channels/", chatHandler::handleGetChannels);
            javalinConfig.routes.get("/chat/channels/{channel_id}", chatHandler::handleGetChannel);
            javalinConfig.routes.get("/chat/channels/{channel_id}/messages", chatHandler::handleGetChannelMessages);
            javalinConfig.routes.post("/chat/channels/{channel_id}/messages", chatHandler::handlePostChannelMessage);
            javalinConfig.routes.put("/chat/channels/{channel_id}/users/{user_id}", chatHandler::handleJoinChannel);
            javalinConfig.routes.delete("/chat/channels/{channel_id}/users/{user_id}", chatHandler::handleLeaveChannel);
            javalinConfig.routes.put("/chat/channels/{channel_id}/mark-as-read/{message_id}", chatHandler::handleMarkAsRead);
            javalinConfig.routes.get("/chat/updates", chatHandler::handleGetUpdates);

            // Synchronized Server stats and Online players
            javalinConfig.routes.get("/api/v1/get_server_stats", ctx -> {
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("online_players", onlineManager.getCombinedOnlineCount());
                stats.put("total_players", databaseManager.countUsers());
                stats.put("maps", databaseManager.countBeatmapsets());
                stats.put("scores", databaseManager.countScores());
                ctx.json(stats);
            });

            javalinConfig.routes.get("/api/v1/online", ctx -> {
                Set<Integer> onlineIds = onlineManager.getCombinedOnlineUserIds();
                List<Map<String, Object>> list = new ArrayList<>();
                for (int uid : onlineIds) {
                    com.osuserverlist.lazer.models.User u = databaseManager.findUserById(uid);
                    if (u != null) {
                        list.add(Map.of("id", u.id, "name", u.name));
                    }
                }
                ctx.json(Map.of(
                        "offset", 0,
                        "limit", list.size(),
                        "count", list.size(),
                        "data", list
                ));
            });

            javalinConfig.routes.get("/api/v2/stats", ctx -> {
                ctx.json(Map.of(
                        "online_players", onlineManager.getCombinedOnlineCount(),
                        "total_players", databaseManager.countUsers(),
                        "maps", databaseManager.countBeatmapsets(),
                        "scores", databaseManager.countScores()
                ));
            });

            // Multiplayer Rooms (REST API)
            javalinConfig.routes.get("/api/v2/rooms", roomHandler::handleGetRooms);
            javalinConfig.routes.get("/api/v2/rooms/", roomHandler::handleGetRooms);
            javalinConfig.routes.post("/api/v2/rooms", roomHandler::handleCreateRoom);
            javalinConfig.routes.post("/api/v2/rooms/", roomHandler::handleCreateRoom);
            javalinConfig.routes.get("/api/v2/rooms/{room_id}", roomHandler::handleGetRoom);
            javalinConfig.routes.get("/api/v2/rooms/{room_id}/", roomHandler::handleGetRoom);
            javalinConfig.routes.delete("/api/v2/rooms/{room_id}", roomHandler::handleDeleteRoom);
            javalinConfig.routes.delete("/api/v2/rooms/{room_id}/", roomHandler::handleDeleteRoom);
            javalinConfig.routes.put("/api/v2/rooms/{room_id}/users/{user_id}", roomHandler::handleJoinRoom);
            javalinConfig.routes.post("/api/v2/rooms/{room_id}/users/{user_id}", roomHandler::handleJoinRoom);
            javalinConfig.routes.delete("/api/v2/rooms/{room_id}/users/{user_id}", roomHandler::handleLeaveRoom);
            javalinConfig.routes.get("/api/v2/rooms/{room_id}/leaderboard", roomHandler::handleGetLeaderboard);
            javalinConfig.routes.get("/api/v2/rooms/{room_id}/events", roomHandler::handleGetRoomEvents);
            javalinConfig.routes.get("/api/v2/rooms/{room_id}/playlist/{playlist_id}/scores", roomHandler::handleGetPlaylistScores);
            javalinConfig.routes.post("/api/v2/rooms/{room_id}/playlist/{playlist_id}/scores", roomHandler::handleCreateScoreToken);
            javalinConfig.routes.put("/api/v2/rooms/{room_id}/playlist/{playlist_id}/scores/{token}", roomHandler::handleSubmitScore);
            javalinConfig.routes.post("/api/v2/rooms/{room_id}/playlist/{playlist_id}/scores/{token}", roomHandler::handleSubmitScore);

            // Legacy non-v2 paths for rooms
            javalinConfig.routes.get("/rooms", roomHandler::handleGetRooms);
            javalinConfig.routes.get("/rooms/", roomHandler::handleGetRooms);
            javalinConfig.routes.post("/rooms", roomHandler::handleCreateRoom);
            javalinConfig.routes.post("/rooms/", roomHandler::handleCreateRoom);
            javalinConfig.routes.get("/rooms/{room_id}", roomHandler::handleGetRoom);
            javalinConfig.routes.get("/rooms/{room_id}/", roomHandler::handleGetRoom);
            javalinConfig.routes.delete("/rooms/{room_id}", roomHandler::handleDeleteRoom);
            javalinConfig.routes.delete("/rooms/{room_id}/", roomHandler::handleDeleteRoom);
            javalinConfig.routes.put("/rooms/{room_id}/users/{user_id}", roomHandler::handleJoinRoom);
            javalinConfig.routes.post("/rooms/{room_id}/users/{user_id}", roomHandler::handleJoinRoom);
            javalinConfig.routes.delete("/rooms/{room_id}/users/{user_id}", roomHandler::handleLeaveRoom);
            javalinConfig.routes.get("/rooms/{room_id}/leaderboard", roomHandler::handleGetLeaderboard);
            javalinConfig.routes.get("/rooms/{room_id}/events", roomHandler::handleGetRoomEvents);
            javalinConfig.routes.get("/rooms/{room_id}/playlist/{playlist_id}/scores", roomHandler::handleGetPlaylistScores);
            javalinConfig.routes.post("/rooms/{room_id}/playlist/{playlist_id}/scores", roomHandler::handleCreateScoreToken);
            javalinConfig.routes.put("/rooms/{room_id}/playlist/{playlist_id}/scores/{token}", roomHandler::handleSubmitScore);
            javalinConfig.routes.post("/rooms/{room_id}/playlist/{playlist_id}/scores/{token}", roomHandler::handleSubmitScore);

            // SignalR Real-time Hubs (Metadata, Spectator, Multiplayer)
            // Route both /signalr/* and direct /* paths for maximum compatibility
            javalinConfig.routes.post("/signalr/metadata/negotiate", metadataHub::handleNegotiate);
            javalinConfig.routes.post("/signalr/spectator/negotiate", spectatorHub::handleNegotiate);
            javalinConfig.routes.post("/signalr/multiplayer/negotiate", multiplayerHub::handleNegotiate);
            javalinConfig.routes.ws("/signalr/metadata", metadataHub::configureWs);
            javalinConfig.routes.ws("/signalr/spectator", spectatorHub::configureWs);
            javalinConfig.routes.ws("/signalr/multiplayer", multiplayerHub::configureWs);

            javalinConfig.routes.post("/metadata/negotiate", metadataHub::handleNegotiate);
            javalinConfig.routes.post("/spectator/negotiate", spectatorHub::handleNegotiate);
            javalinConfig.routes.post("/multiplayer/negotiate", multiplayerHub::handleNegotiate);
            javalinConfig.routes.ws("/metadata", metadataHub::configureWs);
            javalinConfig.routes.ws("/spectator", spectatorHub::configureWs);
            javalinConfig.routes.ws("/multiplayer", multiplayerHub::configureWs);

            // Notification / Chat WebSocket
            javalinConfig.routes.ws("/notification-server", notificationHub::configureWs);
            javalinConfig.routes.ws("/api/v2/notification-server", notificationHub::configureWs);

            // Misc & Assets
            javalinConfig.routes.get("/api/v2/comments", ctx -> ctx.json(Map.of(
                    "comments", List.of(),
                    "has_more", false,
                    "total", 0,
                    "users", List.of()
            )));
            javalinConfig.routes.get("/api/v2/news", ctx -> ctx.json(Map.of("news_posts", List.of())));
            javalinConfig.routes.get("/api/v2/seasonal-backgrounds", miscHandler);
            javalinConfig.routes.get("/api/v1/banner/{file}", miscHandler);
            javalinConfig.routes.get("/a/{id}", miscHandler);
            javalinConfig.routes.get("/avatar/{id}", miscHandler);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down lazer.jar...");
            app.stop();
            databaseManager.close();
        }));

        app.start(config.port);
        logger.info("lazer.jar started successfully on port {}", config.port);
    }
}
