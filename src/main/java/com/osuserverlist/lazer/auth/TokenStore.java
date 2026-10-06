package com.osuserverlist.lazer.auth;

import com.osuserverlist.lazer.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenStore {
    private static final Logger logger = LoggerFactory.getLogger(TokenStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private JedisPool jedisPool;
    private final Map<String, TokenData> memoryAccessTokens = new ConcurrentHashMap<>();
    private final Map<String, TokenData> memoryRefreshTokens = new ConcurrentHashMap<>();

    public static class TokenData {
        public final int userId;
        public final String username;
        public final int privileges;
        public final String scope;
        public final long expiresAt;
        public String associatedRefreshToken;

        public TokenData(int userId, String username, int privileges, String scope, long expiresAt) {
            this.userId = userId;
            this.username = username;
            this.privileges = privileges;
            this.scope = scope;
            this.expiresAt = expiresAt;
        }
    }

    public static class TokenPair {
        public final String accessToken;
        public final String refreshToken;
        public final long expiresIn;

        public TokenPair(String accessToken, String refreshToken, long expiresIn) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
        }
    }

    public void init(ServerConfig config) {
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(16);
            if (config.redisPass != null && !config.redisPass.isBlank()) {
                this.jedisPool = new JedisPool(poolConfig, config.redisHost, config.redisPort, 2000, config.redisPass, config.redisDb);
            } else {
                this.jedisPool = new JedisPool(poolConfig, config.redisHost, config.redisPort, 2000, null, config.redisDb);
            }
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                logger.info("Connected to Redis at {}:{}", config.redisHost, config.redisPort);
            }
        } catch (Exception e) {
            logger.warn("Could not connect to Redis ({}), using in-memory token store", e.getMessage());
            this.jedisPool = null;
        }
    }

    public JedisPool getJedisPool() {
        return jedisPool;
    }

    public TokenPair issue(int userId, String username, int privileges, String scope) {
        String accessToken = generateRandomString(32);
        String refreshToken = generateRandomString(32);
        long expiresIn = 86400; // 24 hours
        long expiresAt = System.currentTimeMillis() + (expiresIn * 1000);

        TokenData data = new TokenData(userId, username, privileges, scope != null ? scope : "*", expiresAt);
        data.associatedRefreshToken = refreshToken;

        if (jedisPool != null) {
            try (Jedis jedis = jedisPool.getResource()) {
                String accessKey = "bjar:oauth:access:" + accessToken;
                String refreshKey = "bjar:oauth:refresh:" + refreshToken;
                jedis.hset(accessKey, Map.of(
                        "userId", String.valueOf(userId),
                        "username", username,
                        "privileges", String.valueOf(privileges),
                        "scope", data.scope
                ));
                jedis.expire(accessKey, expiresIn);

                jedis.hset(refreshKey, Map.of(
                        "userId", String.valueOf(userId),
                        "username", username,
                        "privileges", String.valueOf(privileges),
                        "scope", data.scope
                ));
                jedis.expire(refreshKey, expiresIn * 30);
            } catch (Exception e) {
                logger.warn("Failed to write token to Redis, falling back to memory: {}", e.getMessage());
                memoryAccessTokens.put(accessToken, data);
                memoryRefreshTokens.put(refreshToken, data);
            }
        } else {
            memoryAccessTokens.put(accessToken, data);
            memoryRefreshTokens.put(refreshToken, data);
        }

        return new TokenPair(accessToken, refreshToken, expiresIn);
    }

    public TokenData resolve(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) return null;

        if (jedisPool != null) {
            try (Jedis jedis = jedisPool.getResource()) {
                String accessKey = "bjar:oauth:access:" + accessToken;
                Map<String, String> map = jedis.hgetAll(accessKey);
                if (map != null && !map.isEmpty()) {
                    int userId = Integer.parseInt(map.getOrDefault("userId", "0"));
                    String username = map.getOrDefault("username", "");
                    int priv = Integer.parseInt(map.getOrDefault("privileges", "1"));
                    String scope = map.getOrDefault("scope", "*");
                    return new TokenData(userId, username, priv, scope, System.currentTimeMillis() + 3600000);
                }
            } catch (Exception e) {
                logger.debug("Redis resolve failed: {}", e.getMessage());
            }
        }

        TokenData data = memoryAccessTokens.get(accessToken);
        if (data != null) {
            if (System.currentTimeMillis() > data.expiresAt) {
                memoryAccessTokens.remove(accessToken);
                return null;
            }
            return data;
        }

        return null;
    }

    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return null;

        TokenData data = null;
        if (jedisPool != null) {
            try (Jedis jedis = jedisPool.getResource()) {
                String refreshKey = "bjar:oauth:refresh:" + refreshToken;
                Map<String, String> map = jedis.hgetAll(refreshKey);
                if (map != null && !map.isEmpty()) {
                    int userId = Integer.parseInt(map.getOrDefault("userId", "0"));
                    String username = map.getOrDefault("username", "");
                    int priv = Integer.parseInt(map.getOrDefault("privileges", "1"));
                    String scope = map.getOrDefault("scope", "*");
                    data = new TokenData(userId, username, priv, scope, 0);
                    jedis.del(refreshKey);
                }
            } catch (Exception e) {
                logger.debug("Redis refresh failed: {}", e.getMessage());
            }
        }

        if (data == null) {
            data = memoryRefreshTokens.remove(refreshToken);
        }

        if (data != null) {
            return issue(data.userId, data.username, data.privileges, data.scope);
        }

        return null;
    }

    private String generateRandomString(int bytesCount) {
        byte[] bytes = new byte[bytesCount];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
