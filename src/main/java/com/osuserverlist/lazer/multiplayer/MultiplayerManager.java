package com.osuserverlist.lazer.multiplayer;

import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.BeatmapRecord;
import com.osuserverlist.lazer.models.MultiplayerRoomData.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class MultiplayerManager {
    private static final Logger logger = LoggerFactory.getLogger(MultiplayerManager.class);

    private final DatabaseManager databaseManager;
    private final Map<Long, Room> rooms = new ConcurrentHashMap<>();
    private final Map<Integer, Long> userActiveRoom = new ConcurrentHashMap<>();

    private final AtomicLong nextRoomId = new AtomicLong(1);
    private final AtomicLong nextPlaylistItemId = new AtomicLong(1);
    private final AtomicLong nextEventId = new AtomicLong(1);
    private final AtomicLong nextTokenId = new AtomicLong(1000000000L);

    // Events per room: roomId -> list of RoomEvent
    private final Map<Long, List<RoomEvent>> roomEvents = new ConcurrentHashMap<>();

    // Scores per room playlist item: roomId -> (playlistItemId -> list of score maps)
    private final Map<Long, Map<Long, List<Map<String, Object>>>> roomScores = new ConcurrentHashMap<>();

    // Attempt stats per room: roomId -> (userId -> RoomUserAttemptStats)
    private final Map<Long, Map<Integer, RoomUserAttemptStats>> roomAttempts = new ConcurrentHashMap<>();

    // Active score tokens for multiplayer/playlist rooms: tokenId -> RoomScoreToken
    private final Map<Long, RoomScoreToken> scoreTokens = new ConcurrentHashMap<>();

    public MultiplayerManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Room createRoom(int hostUserId, String name, String password, int matchType, int queueMode,
                           Integer duration, Integer maxAttempts,
                           List<PlaylistItem> playlistItems, String category) {
        long roomId = nextRoomId.getAndIncrement();
        Room room = new Room(roomId, hostUserId);
        if (category != null && !category.isBlank()) {
            room.category = category.toLowerCase();
        }

        room.name = (name != null && !name.isBlank()) ? name : "Multiplayer Room " + roomId;
        room.settings.name = room.name;
        room.settings.password = (password != null) ? password : "";
        room.settings.matchType = matchType;
        room.settings.queueMode = queueMode;
        room.duration = duration;
        room.maxAttempts = maxAttempts;

        if (duration != null && duration > 0) {
            room.endsAt = room.startsAt + (duration * 60L * 1000L);
        }

        if (playlistItems != null && !playlistItems.isEmpty()) {
            for (PlaylistItem it : playlistItems) {
                if (it.id <= 0) {
                    it.id = nextPlaylistItemId.getAndIncrement();
                }
                if (it.ownerId <= 0) {
                    it.ownerId = hostUserId;
                }
                populateBeatmapDetails(it);
                room.playlist.add(it);
            }
            room.settings.playlistItemId = room.playlist.get(0).id;
        } else {
            // Default playlist item
            PlaylistItem item = new PlaylistItem();
            item.id = nextPlaylistItemId.getAndIncrement();
            item.ownerId = hostUserId;
            item.beatmapId = 1;
            item.rulesetId = 0;
            populateBeatmapDetails(item);
            room.playlist.add(item);
            room.settings.playlistItemId = item.id;
        }

        // Add host as active user
        if (hostUserId > 0) {
            RoomUser hostUser = new RoomUser(hostUserId);
            hostUser.state = UserState.IDLE;
            room.users.put(hostUserId, hostUser);
            userActiveRoom.put(hostUserId, roomId);
        }

        rooms.put(roomId, room);
        roomEvents.put(roomId, new CopyOnWriteArrayList<>());
        roomScores.put(roomId, new ConcurrentHashMap<>());
        roomAttempts.put(roomId, new ConcurrentHashMap<>());

        addEvent(roomId, hostUserId, room.settings.playlistItemId, "room_created", Map.of("name", room.name));

        logger.info("Created room {} [{}] category={} host={}", roomId, room.name, room.category, hostUserId);
        return room;
    }

    public Room createRoom(int hostUserId, String name, String password, int matchType, int queueMode,
                           int beatmapId, String checksum, int rulesetId,
                           List<Map<String, Object>> requiredMods, List<Map<String, Object>> allowedMods,
                           String category) {
        PlaylistItem item = new PlaylistItem();
        item.beatmapId = beatmapId > 0 ? beatmapId : 1;
        item.beatmapChecksum = checksum != null ? checksum : "";
        item.rulesetId = rulesetId;
        if (requiredMods != null) item.requiredMods.addAll(requiredMods);
        if (allowedMods != null) item.allowedMods.addAll(allowedMods);

        return createRoom(hostUserId, name, password, matchType, queueMode, null, null, List.of(item), category);
    }

    public Room getRoom(long roomId) {
        return rooms.get(roomId);
    }

    public Long getUserCurrentRoomId(int userId) {
        return userActiveRoom.get(userId);
    }

    public List<Room> getRooms(String category, String mode, String status) {
        return getRooms(category, mode, status, 0);
    }

    public List<Room> getRooms(String category, String mode, String status, int currentUserId) {
        List<Room> result = new ArrayList<>();

        for (Room room : rooms.values()) {
            // Filter category
            if (category != null && !category.equalsIgnoreCase("all") && !category.isBlank()) {
                if (!room.category.equalsIgnoreCase(category)) {
                    continue;
                }
            }
            // Filter status
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase("all")) {
                String roomStatusStr = switch (room.state) {
                    case WAITING_FOR_LOAD, PLAYING -> "playing";
                    case CLOSED -> "ended";
                    default -> "idle";
                };
                if (!roomStatusStr.equalsIgnoreCase(status)) {
                    continue;
                }
            }
            // Filter mode
            if ("open".equalsIgnoreCase(mode)) {
                if (room.isEnded()) continue;
            } else if ("ended".equalsIgnoreCase(mode)) {
                if (!room.isEnded()) continue;
            } else if ("participated".equalsIgnoreCase(mode)) {
                if (currentUserId <= 0 || !room.recentParticipants.contains(currentUserId)) {
                    continue;
                }
            } else if ("owned".equalsIgnoreCase(mode)) {
                if (currentUserId <= 0 || room.hostUserId != currentUserId) {
                    continue;
                }
            }

            result.add(room);
        }

        // Sort: active rooms first, then by last activity descending
        result.sort((a, b) -> {
            boolean aEnded = a.isEnded();
            boolean bEnded = b.isEnded();
            if (aEnded != bEnded) {
                return aEnded ? 1 : -1;
            }
            return Long.compare(b.lastActivity, a.lastActivity);
        });

        return result;
    }

    public Room joinRoom(long roomId, int userId, String password) {
        Room room = rooms.get(roomId);
        if (room == null || room.isEnded()) {
            return null;
        }

        // Check password if set
        if (room.settings.password != null && !room.settings.password.isBlank()) {
            if (password == null || !password.equals(room.settings.password)) {
                return null;
            }
        }

        // Leave previous room if any
        Long prevRoomId = userActiveRoom.get(userId);
        if (prevRoomId != null && prevRoomId != roomId) {
            leaveRoom(prevRoomId, userId);
        }

        if (userId > 0) {
            RoomUser user = room.users.computeIfAbsent(userId, RoomUser::new);
            user.state = UserState.IDLE;
            room.recentParticipants.add(userId);
            userActiveRoom.put(userId, roomId);

            // If no host, assign new user as host
            if (room.hostUserId <= 0 || !room.users.containsKey(room.hostUserId)) {
                room.hostUserId = userId;
            }

            addEvent(roomId, userId, null, "user_joined", Map.of());
            logger.info("User {} joined room {}", userId, roomId);
        }

        room.lastActivity = System.currentTimeMillis();
        return room;
    }

    public RoomUser leaveRoom(long roomId, int userId) {
        Room room = rooms.get(roomId);
        if (room == null) return null;

        RoomUser removed = room.users.remove(userId);
        userActiveRoom.remove(userId);
        room.lastActivity = System.currentTimeMillis();

        if (removed != null) {
            addEvent(roomId, userId, null, "user_left", Map.of());
            logger.info("User {} left room {}", userId, roomId);
        }

        // If host left, elect new host or close room
        if (room.hostUserId == userId) {
            if (!room.users.isEmpty()) {
                room.hostUserId = room.users.keySet().iterator().next();
                addEvent(roomId, room.hostUserId, null, "host_changed", Map.of("new_host_id", room.hostUserId));
                logger.info("Room {} host migrated to {}", roomId, room.hostUserId);
            } else {
                room.state = RoomState.CLOSED;
                room.endsAt = System.currentTimeMillis();
                addEvent(roomId, null, null, "room_ended", Map.of());
                logger.info("Room {} closed as all users left", roomId);
            }
        }

        return removed;
    }

    public boolean closeRoom(long roomId, int userId) {
        Room room = rooms.get(roomId);
        if (room == null) return false;
        if (userId > 0 && room.hostUserId != userId) return false;

        room.state = RoomState.CLOSED;
        room.endsAt = System.currentTimeMillis();
        room.lastActivity = System.currentTimeMillis();
        addEvent(roomId, userId > 0 ? userId : null, null, "room_ended", Map.of());
        logger.info("Room {} closed by user {}", roomId, userId);
        return true;
    }

    public boolean changeSettings(long roomId, int hostUserId, RoomSettings newSettings) {
        Room room = rooms.get(roomId);
        if (room == null || (hostUserId > 0 && room.hostUserId != hostUserId)) return false;

        if (newSettings.name != null && !newSettings.name.isBlank()) {
            room.name = newSettings.name;
            room.settings.name = newSettings.name;
        }
        room.settings.password = (newSettings.password != null) ? newSettings.password : "";
        room.settings.matchType = newSettings.matchType;
        room.settings.queueMode = newSettings.queueMode;
        room.settings.autoStartDurationMs = newSettings.autoStartDurationMs;
        room.settings.autoSkip = newSettings.autoSkip;
        room.settings.maxParticipants = newSettings.maxParticipants;
        if (newSettings.playlistItemId > 0 && room.hasPlaylistItem(newSettings.playlistItemId)) {
            room.settings.playlistItemId = newSettings.playlistItemId;
        }
        room.lastActivity = System.currentTimeMillis();
        addEvent(roomId, hostUserId, room.settings.playlistItemId, "settings_changed", Map.of("name", room.settings.name));
        return true;
    }

    public boolean changeUserState(long roomId, int userId, UserState newState) {
        Room room = rooms.get(roomId);
        if (room == null) return false;

        RoomUser user = room.users.get(userId);
        if (user == null) return false;

        user.state = newState;
        room.lastActivity = System.currentTimeMillis();
        return true;
    }

    public boolean changeBeatmapAvailability(long roomId, int userId, BeatmapAvailability availability) {
        Room room = rooms.get(roomId);
        if (room == null) return false;

        RoomUser user = room.users.get(userId);
        if (user == null) return false;

        user.beatmapAvailability = availability;
        room.lastActivity = System.currentTimeMillis();
        return true;
    }

    public boolean changeUserMods(long roomId, int userId, List<Map<String, Object>> mods) {
        Room room = rooms.get(roomId);
        if (room == null) return false;

        RoomUser user = room.users.get(userId);
        if (user == null) return false;

        user.mods.clear();
        if (mods != null) {
            user.mods.addAll(mods);
        }
        room.lastActivity = System.currentTimeMillis();
        return true;
    }

    public boolean changeUserStyle(long roomId, int userId, Integer beatmapId, Integer rulesetId) {
        Room room = rooms.get(roomId);
        if (room == null) return false;

        RoomUser user = room.users.get(userId);
        if (user == null) return false;

        user.beatmapId = beatmapId;
        user.rulesetId = rulesetId;
        room.lastActivity = System.currentTimeMillis();
        return true;
    }

    public PlaylistItem addPlaylistItem(long roomId, int userId, PlaylistItem item) {
        Room room = rooms.get(roomId);
        if (room == null) return null;

        item.id = nextPlaylistItemId.getAndIncrement();
        item.ownerId = userId > 0 ? userId : room.hostUserId;
        item.playlistOrder = room.playlist.size();
        item.createdAt = System.currentTimeMillis();
        populateBeatmapDetails(item);

        room.playlist.add(item);
        room.lastActivity = System.currentTimeMillis();
        addEvent(roomId, userId, item.id, "playlist_item_added", Map.of("item_id", item.id, "beatmap_id", item.beatmapId));
        return item;
    }

    public PlaylistItem editPlaylistItem(long roomId, int userId, PlaylistItem item) {
        Room room = rooms.get(roomId);
        if (room == null) return null;

        populateBeatmapDetails(item);

        for (int i = 0; i < room.playlist.size(); i++) {
            PlaylistItem existing = room.playlist.get(i);
            if (existing.id == item.id) {
                item.ownerId = existing.ownerId;
                item.playlistOrder = existing.playlistOrder;
                item.createdAt = existing.createdAt;
                room.playlist.set(i, item);
                room.lastActivity = System.currentTimeMillis();
                addEvent(roomId, userId, item.id, "playlist_item_changed", Map.of("item_id", item.id, "beatmap_id", item.beatmapId));
                return item;
            }
        }

        if (!room.playlist.isEmpty()) {
            PlaylistItem current = room.getCurrentPlaylistItem();
            if (current != null) {
                item.id = current.id;
                item.ownerId = current.ownerId;
                item.playlistOrder = current.playlistOrder;
                int idx = room.playlist.indexOf(current);
                if (idx >= 0) {
                    room.playlist.set(idx, item);
                } else {
                    room.playlist.set(0, item);
                }
                room.lastActivity = System.currentTimeMillis();
                addEvent(roomId, userId, item.id, "playlist_item_changed", Map.of("item_id", item.id, "beatmap_id", item.beatmapId));
                return item;
            }
        }

        return null;
    }

    public boolean removePlaylistItem(long roomId, int userId, long playlistItemId) {
        Room room = rooms.get(roomId);
        if (room == null) return false;

        boolean removed = room.playlist.removeIf(item -> item.id == playlistItemId);
        if (removed) {
            room.lastActivity = System.currentTimeMillis();
            addEvent(roomId, userId, playlistItemId, "playlist_item_removed", Map.of("item_id", playlistItemId));
        }
        return removed;
    }

    public void addEvent(long roomId, Integer userId, Long playlistItemId, String type, Map<String, Object> details) {
        List<RoomEvent> list = roomEvents.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>());
        RoomEvent event = new RoomEvent(nextEventId.getAndIncrement(), roomId, userId, playlistItemId, type);
        if (details != null) {
            event.details.putAll(details);
        }
        list.add(event);
    }

    public List<RoomEvent> getRoomEvents(long roomId, Integer after, Integer before, int limit) {
        List<RoomEvent> list = roomEvents.get(roomId);
        if (list == null) return Collections.emptyList();

        List<RoomEvent> result = new ArrayList<>();
        int max = Math.min(Math.max(limit, 1), 1000);

        for (int i = list.size() - 1; i >= 0; i--) {
            RoomEvent ev = list.get(i);
            if (after != null && ev.id <= after) continue;
            if (before != null && ev.id >= before) continue;
            result.add(ev);
            if (result.size() >= max) break;
        }
        return result;
    }

    public RoomScoreToken createScoreToken(long roomId, long playlistItemId, int userId, int beatmapId, int rulesetId, String beatmapHash) {
        long tokenId = nextTokenId.getAndIncrement();
        RoomScoreToken token = new RoomScoreToken(tokenId, roomId, playlistItemId, userId, beatmapId, rulesetId, beatmapHash);
        scoreTokens.put(tokenId, token);
        return token;
    }

    public RoomScoreToken getScoreToken(long tokenId) {
        return scoreTokens.get(tokenId);
    }

    public RoomScoreToken consumeScoreToken(long tokenId) {
        return scoreTokens.remove(tokenId);
    }

    public void recordScore(long roomId, long playlistItemId, Map<String, Object> scoreMap, int userId,
                            long totalScore, double accuracy, int maxCombo, float pp) {
        Map<Long, List<Map<String, Object>>> roomScoreMap = roomScores.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
        List<Map<String, Object>> list = roomScoreMap.computeIfAbsent(playlistItemId, k -> new CopyOnWriteArrayList<>());

        // Replace previous score if this one is higher or add
        boolean updated = false;
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> existing = list.get(i);
            int existingUserId = 0;
            if (existing.get("user") instanceof Map<?, ?> uMap && uMap.get("id") instanceof Number uid) {
                existingUserId = uid.intValue();
            } else if (existing.get("user_id") instanceof Number uid) {
                existingUserId = uid.intValue();
            }
            if (existingUserId == userId) {
                long existingScore = (existing.get("total_score") instanceof Number n) ? n.longValue() : 0;
                if (totalScore >= existingScore) {
                    list.set(i, scoreMap);
                }
                updated = true;
                break;
            }
        }
        if (!updated) {
            list.add(scoreMap);
        }

        // Update attempts and aggregate stats
        Map<Integer, RoomUserAttemptStats> attempts = roomAttempts.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
        RoomUserAttemptStats stats = attempts.computeIfAbsent(userId, k -> {
            RoomUserAttemptStats s = new RoomUserAttemptStats();
            s.userId = userId;
            return s;
        });
        stats.attempts++;
        stats.totalScore += totalScore;
        stats.totalAccuracy = ((stats.totalAccuracy * (stats.attempts - 1)) + accuracy) / stats.attempts;
        stats.maxCombo = Math.max(stats.maxCombo, maxCombo);
        stats.totalPp += pp;

        Room room = rooms.get(roomId);
        if (room != null) {
            room.lastActivity = System.currentTimeMillis();
        }

        addEvent(roomId, userId, playlistItemId, "score_submitted", Map.of(
                "score_id", scoreMap.getOrDefault("id", 0),
                "total_score", totalScore,
                "accuracy", accuracy
        ));
    }

    public List<Map<String, Object>> getPlaylistScores(long roomId, long playlistItemId) {
        Map<Long, List<Map<String, Object>>> roomScoreMap = roomScores.get(roomId);
        if (roomScoreMap == null) return Collections.emptyList();
        List<Map<String, Object>> list = roomScoreMap.get(playlistItemId);
        if (list == null) return Collections.emptyList();

        List<Map<String, Object>> copy = new ArrayList<>(list);
        copy.sort((a, b) -> {
            long scoreA = (a.get("total_score") instanceof Number n) ? n.longValue() : 0;
            long scoreB = (b.get("total_score") instanceof Number n) ? n.longValue() : 0;
            return Long.compare(scoreB, scoreA);
        });
        return copy;
    }

    public Map<String, Object> getPlaylistScore(long roomId, long playlistItemId, long scoreId) {
        List<Map<String, Object>> scores = getPlaylistScores(roomId, playlistItemId);
        for (Map<String, Object> sc : scores) {
            if (sc.get("id") instanceof Number n && n.longValue() == scoreId) {
                return sc;
            }
        }
        return null;
    }

    public Map<String, Object> getUserPlaylistScore(long roomId, long playlistItemId, int userId) {
        List<Map<String, Object>> scores = getPlaylistScores(roomId, playlistItemId);
        for (Map<String, Object> sc : scores) {
            if (sc.get("user") instanceof Map<?, ?> uMap && uMap.get("id") instanceof Number uid) {
                if (uid.intValue() == userId) {
                    return sc;
                }
            } else if (sc.get("user_id") instanceof Number uid && uid.intValue() == userId) {
                return sc;
            }
        }
        return null;
    }

    public List<RoomUserAttemptStats> getRoomLeaderboard(long roomId) {
        Map<Integer, RoomUserAttemptStats> attempts = roomAttempts.get(roomId);
        if (attempts == null) return Collections.emptyList();

        List<RoomUserAttemptStats> list = new ArrayList<>(attempts.values());
        list.sort((a, b) -> Long.compare(b.totalScore, a.totalScore));
        return list;
    }

    public RoomUserAttemptStats getUserRoomAttempts(long roomId, int userId) {
        Map<Integer, RoomUserAttemptStats> attempts = roomAttempts.get(roomId);
        if (attempts == null) return null;
        return attempts.get(userId);
    }

    public boolean transferHost(long roomId, int hostUserId, int targetUserId) {
        Room room = rooms.get(roomId);
        if (room == null || (hostUserId > 0 && room.hostUserId != hostUserId)) return false;
        if (!room.users.containsKey(targetUserId)) return false;

        room.hostUserId = targetUserId;
        room.lastActivity = System.currentTimeMillis();
        addEvent(roomId, targetUserId, null, "host_changed", Map.of("new_host_id", targetUserId));
        logger.info("Room {} host transferred from {} to {}", roomId, hostUserId, targetUserId);
        return true;
    }

    public RoomUser kickUser(long roomId, int hostUserId, int targetUserId) {
        Room room = rooms.get(roomId);
        if (room == null || (hostUserId > 0 && room.hostUserId != hostUserId)) return null;
        if (hostUserId == targetUserId) return null;

        addEvent(roomId, targetUserId, null, "user_kicked", Map.of("kicked_by", hostUserId));
        return leaveRoom(roomId, targetUserId);
    }

    public boolean startMatch(long roomId, int hostUserId) {
        Room room = rooms.get(roomId);
        if (room == null || (hostUserId > 0 && room.hostUserId != hostUserId)) return false;

        room.state = RoomState.WAITING_FOR_LOAD;
        for (RoomUser u : room.users.values()) {
            u.state = UserState.WAITING_FOR_LOAD;
        }
        room.lastActivity = System.currentTimeMillis();
        addEvent(roomId, hostUserId, room.settings.playlistItemId, "match_started", Map.of());
        logger.info("Match starting in room {}", roomId);
        return true;
    }

    public boolean abortMatch(long roomId) {
        Room room = rooms.get(roomId);
        if (room == null) return false;

        room.state = RoomState.OPEN;
        for (RoomUser u : room.users.values()) {
            u.state = UserState.IDLE;
        }
        room.lastActivity = System.currentTimeMillis();
        addEvent(roomId, null, null, "match_aborted", Map.of());
        return true;
    }

    private void populateBeatmapDetails(PlaylistItem item) {
        if (databaseManager == null) return;
        BeatmapRecord br = null;
        if (item.beatmapId > 0) {
            br = databaseManager.findBeatmapById(item.beatmapId);
        }
        if (br == null && item.beatmapChecksum != null && !item.beatmapChecksum.isBlank()) {
            br = databaseManager.findBeatmapByMd5(item.beatmapChecksum);
        }
        if (br != null) {
            if (item.beatmapId <= 0) item.beatmapId = br.id;
            if (item.beatmapChecksum == null || item.beatmapChecksum.isBlank()) {
                item.beatmapChecksum = (br.md5 != null) ? br.md5 : "";
            }
            if (item.starRating <= 0.0) {
                item.starRating = br.diff;
            }
        }
    }
}
