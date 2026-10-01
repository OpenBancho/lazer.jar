package com.osuserverlist.lazer;

import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.handlers.*;
import io.github.cdimascio.dotenv.Dotenv;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

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

        AuthService authService = new AuthService(databaseManager, tokenStore);

        OAuthHandler oauthHandler = new OAuthHandler(authService);
        MeHandler meHandler = new MeHandler(authService, databaseManager, config);
        UserHandler userHandler = new UserHandler(databaseManager, config);
        UserScoresHandler userScoresHandler = new UserScoresHandler(databaseManager, config);
        RelationshipsHandler relationshipsHandler = new RelationshipsHandler(authService, databaseManager);
        NotificationsHandler notificationsHandler = new NotificationsHandler();
        ChatHandler chatHandler = new ChatHandler();
        MiscHandler miscHandler = new MiscHandler();
        SoloScoreHandler soloScoreHandler = new SoloScoreHandler(authService, databaseManager, config);
        BeatmapHandler beatmapHandler = new BeatmapHandler(databaseManager, config);
        RankingsHandler rankingsHandler = new RankingsHandler(databaseManager, config);

        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost();
                });
            });
            javalinConfig.requestLogger.http((ctx, ms) -> {
                logger.info("{} {} -> {} ({} ms)", ctx.method(), ctx.path(), ctx.status(), ms);
            });

            // Health & Info
            javalinConfig.routes.get("/", ctx -> {
                ctx.contentType("application/json");
                ctx.json(Map.of(
                        "server", config.serverName,
                        "status", "online",
                        "version", config.serverVersion
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
            javalinConfig.routes.get("/api/v2/beatmaps/{beatmap_id}/scores", soloScoreHandler::handleGetBeatmapScores);
            javalinConfig.routes.get("/api/v2/beatmapsets/{beatmapset_id}", beatmapHandler::handleGetBeatmapset);
            javalinConfig.routes.get("/api/v2/beatmapsets/lookup", beatmapHandler::handleLookupBeatmapset);
            javalinConfig.routes.get("/api/v2/beatmaps/{beatmap_id}", beatmapHandler::handleGetBeatmap);
            javalinConfig.routes.get("/api/v2/beatmaps/lookup", beatmapHandler::handleLookupBeatmap);

            // Friends & Blocks
            javalinConfig.routes.get("/api/v2/friends", relationshipsHandler);
            javalinConfig.routes.get("/api/v2/blocks", relationshipsHandler);

            // Notifications
            javalinConfig.routes.get("/api/v2/notifications", notificationsHandler);
            javalinConfig.routes.post("/api/v2/notifications/mark-read", notificationsHandler);

            // Chat
            javalinConfig.routes.post("/api/v2/chat/ack", chatHandler);
            javalinConfig.routes.get("/api/v2/chat/channels", chatHandler);
            javalinConfig.routes.get("/api/v2/chat/updates", chatHandler);

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
