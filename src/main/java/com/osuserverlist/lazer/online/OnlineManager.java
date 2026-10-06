package com.osuserverlist.lazer.online;

import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class OnlineManager {
    private static final Logger logger = LoggerFactory.getLogger(OnlineManager.class);
    private final TokenStore tokenStore;
    private final DatabaseManager databaseManager;
    private final ConcurrentHashMap<Integer, Long> localLazerUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> lastDbActivityUpdate = new ConcurrentHashMap<>();

    public OnlineManager(TokenStore tokenStore, DatabaseManager databaseManager) {
        this.tokenStore = tokenStore;
        this.databaseManager = databaseManager;
    }

    public void markUserActive(int userId) {
        if (userId <= 0) return;
        long now = System.currentTimeMillis();
        localLazerUsers.put(userId, now);

        // Update database latest_activity at most once per 60 seconds
        Long lastDb = lastDbActivityUpdate.get(userId);
        if (lastDb == null || now - lastDb > 60_000) {
            lastDbActivityUpdate.put(userId, now);
            if (databaseManager != null) {
                databaseManager.updateUserLatestActivity(userId);
            }
        }

        JedisPool pool = tokenStore.getJedisPool();
        if (pool != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.zadd("bjar:online:lazer", (double) now, String.valueOf(userId));
            } catch (Exception e) {
                logger.debug("Failed to record active user in Redis: {}", e.getMessage());
            }
        }
    }

    public Set<Integer> getCombinedOnlineUserIds() {
        long now = System.currentTimeMillis();
        long cutoff = now - 120_000; // 2 minutes window

        // Clean local map
        localLazerUsers.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        Set<Integer> onlineIds = new HashSet<>(localLazerUsers.keySet());

        JedisPool pool = tokenStore.getJedisPool();
        if (pool != null) {
            try (Jedis jedis = pool.getResource()) {
                // Clean up stale lazer users in Redis
                jedis.zremrangeByScore("bjar:online:lazer", 0, cutoff);
                List<String> lazerList = jedis.zrangeByScore("bjar:online:lazer", cutoff, Double.MAX_VALUE);
                if (lazerList != null) {
                    for (String s : lazerList) {
                        try {
                            onlineIds.add(Integer.parseInt(s));
                        } catch (NumberFormatException ignored) {}
                    }
                }

                // Bancho.jar online players set
                Set<String> banchoSet = jedis.smembers("bjar:online:bancho");
                if (banchoSet != null) {
                    for (String s : banchoSet) {
                        try {
                            onlineIds.add(Integer.parseInt(s));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to fetch online users from Redis: {}", e.getMessage());
            }
        }
        return onlineIds;
    }

    public boolean isUserOnline(int userId) {
        if (userId <= 0) return false;
        return getCombinedOnlineUserIds().contains(userId);
    }

    public int getCombinedOnlineCount() {
        return getCombinedOnlineUserIds().size();
    }
}
