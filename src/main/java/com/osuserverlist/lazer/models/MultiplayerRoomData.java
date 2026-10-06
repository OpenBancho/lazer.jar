package com.osuserverlist.lazer.models;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class MultiplayerRoomData {

    public enum RoomState {
        OPEN(0),
        WAITING_FOR_LOAD(1),
        PLAYING(2),
        CLOSED(3);

        public final int value;
        RoomState(int value) { this.value = value; }

        public static RoomState fromInt(int val) {
            for (RoomState s : values()) {
                if (s.value == val) return s;
            }
            return OPEN;
        }
    }

    public enum UserState {
        IDLE(0),
        READY(1),
        WAITING_FOR_LOAD(2),
        LOADED(3),
        READY_FOR_GAMEPLAY(4),
        PLAYING(5),
        FINISHED_PLAY(6),
        RESULTS(7),
        SPECTATING(8);

        public final int value;
        UserState(int value) { this.value = value; }

        public static UserState fromInt(int val) {
            for (UserState s : values()) {
                if (s.value == val) return s;
            }
            return IDLE;
        }
    }

    public static class BeatmapAvailability {
        public int state = 4; // 4 = LocallyAvailable, 0 = Unknown
        public Double downloadProgress = null;

        public BeatmapAvailability() {}
        public BeatmapAvailability(int state, Double downloadProgress) {
            this.state = state;
            this.downloadProgress = downloadProgress;
        }
    }

    public static class RoomSettings {
        public String name = "Multiplayer Room";
        public long playlistItemId = 1;
        public String password = "";
        public int matchType = 1; // 0 = Playlists, 1 = HeadToHead, 2 = TeamVersus, 3 = Matchmaking
        public int queueMode = 0; // 0 = HostOnly, 1 = AllPlayers, 2 = AllPlayersRoundRobin
        public long autoStartDurationMs = 0;
        public boolean autoSkip = false;
        public Integer maxParticipants = 16;

        public RoomSettings() {}

        public RoomSettings(RoomSettings other) {
            this.name = other.name;
            this.playlistItemId = other.playlistItemId;
            this.password = other.password;
            this.matchType = other.matchType;
            this.queueMode = other.queueMode;
            this.autoStartDurationMs = other.autoStartDurationMs;
            this.autoSkip = other.autoSkip;
            this.maxParticipants = other.maxParticipants;
        }
    }

    public static class RoomUser {
        public final int userId;
        public UserState state = UserState.IDLE;
        public BeatmapAvailability beatmapAvailability = new BeatmapAvailability();
        public List<Map<String, Object>> mods = new CopyOnWriteArrayList<>();
        public Integer rulesetId = null;
        public Integer beatmapId = null;
        public boolean votedToSkipIntro = false;
        public int role = 0; // 0 = None, 1 = Referee

        public RoomUser(int userId) {
            this.userId = userId;
        }
    }

    public static class PlaylistItem {
        public long id;
        public int ownerId;
        public int beatmapId;
        public String beatmapChecksum = "";
        public int rulesetId = 0;
        public List<Map<String, Object>> requiredMods = new CopyOnWriteArrayList<>();
        public List<Map<String, Object>> allowedMods = new CopyOnWriteArrayList<>();
        public boolean expired = false;
        public int playlistOrder = 0;
        public double starRating = 0.0;
        public boolean freestyle = false;
        public String winCondition = null; // "total_score", "accuracy", "combo", "pp"
        public Long playedAt = null;
        public long createdAt = System.currentTimeMillis();

        public PlaylistItem() {}
    }

    public static class Room {
        public final long roomId;
        public String name = "Multiplayer Room";
        public String category = "realtime"; // "normal", "realtime", "daily_challenge", "spotlight"
        public RoomState state = RoomState.OPEN;
        public RoomSettings settings = new RoomSettings();
        public int hostUserId;
        public final Map<Integer, RoomUser> users = new ConcurrentHashMap<>();
        public final List<PlaylistItem> playlist = new CopyOnWriteArrayList<>();
        public int channelId;
        public final Set<Integer> recentParticipants = Collections.newSetFromMap(new ConcurrentHashMap<>());
        public Integer duration = null; // in minutes (for playlist rooms)
        public Integer maxAttempts = null; // for playlist rooms
        public long startsAt = System.currentTimeMillis();
        public Long endsAt = null; // epoch ms when room ends (or ended)
        public long createdAt = System.currentTimeMillis();
        public long lastActivity = System.currentTimeMillis();

        public Room(long roomId, int hostUserId) {
            this.roomId = roomId;
            this.hostUserId = hostUserId;
            this.channelId = (int) (1000 + roomId);
            if (hostUserId > 0) {
                recentParticipants.add(hostUserId);
            }
        }

        public boolean isEnded() {
            if (state == RoomState.CLOSED) return true;
            if (endsAt != null && endsAt <= System.currentTimeMillis()) return true;
            return false;
        }

        public boolean hasPlaylistItem(long id) {
            for (PlaylistItem item : playlist) {
                if (item.id == id) return true;
            }
            return false;
        }

        public PlaylistItem getCurrentPlaylistItem() {
            for (PlaylistItem item : playlist) {
                if (item.id == settings.playlistItemId && !item.expired) {
                    return item;
                }
            }
            for (PlaylistItem item : playlist) {
                if (item.id == settings.playlistItemId) {
                    return item;
                }
            }
            for (PlaylistItem item : playlist) {
                if (!item.expired) {
                    return item;
                }
            }
            return !playlist.isEmpty() ? playlist.get(playlist.size() - 1) : null;
        }

        public Map<String, Double> getDifficultyRange() {
            double min = Double.MAX_VALUE;
            double max = Double.MIN_VALUE;
            boolean hasDiff = false;
            for (PlaylistItem item : playlist) {
                if (item.starRating > 0) {
                    min = Math.min(min, item.starRating);
                    max = Math.max(max, item.starRating);
                    hasDiff = true;
                }
            }
            if (!hasDiff) {
                min = 0.0;
                max = 0.0;
            }
            Map<String, Double> range = new LinkedHashMap<>();
            range.put("min", min);
            range.put("max", max);
            return range;
        }

        public Map<String, Object> getPlaylistItemStats() {
            int countActive = 0;
            int countTotal = playlist.size();
            Set<Integer> rulesetIds = new LinkedHashSet<>();
            for (PlaylistItem item : playlist) {
                if (!item.expired) {
                    countActive++;
                }
                rulesetIds.add(item.rulesetId);
            }
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("count_active", countActive);
            stats.put("count_total", countTotal);
            stats.put("ruleset_ids", new ArrayList<>(rulesetIds));
            return stats;
        }
    }

    public static class RoomEvent {
        public long id;
        public long roomId;
        public Integer userId;
        public Long playlistItemId;
        public String type;
        public long createdAt = System.currentTimeMillis();
        public Map<String, Object> details = new HashMap<>();

        public RoomEvent(long id, long roomId, Integer userId, Long playlistItemId, String type) {
            this.id = id;
            this.roomId = roomId;
            this.userId = userId;
            this.playlistItemId = playlistItemId;
            this.type = type;
        }
    }

    public static class RoomUserAttemptStats {
        public int userId;
        public int attempts = 0;
        public long totalScore = 0;
        public double totalAccuracy = 0.0;
        public int maxCombo = 0;
        public float totalPp = 0.0f;
    }

    public static class RoomScoreToken {
        public final long id;
        public final String token;
        public final long roomId;
        public final long playlistItemId;
        public final int userId;
        public final int beatmapId;
        public final int rulesetId;
        public final String beatmapHash;
        public final long createdAtEpochMs;

        public RoomScoreToken(long id, long roomId, long playlistItemId, int userId, int beatmapId, int rulesetId, String beatmapHash) {
            this.id = id;
            this.token = String.valueOf(id);
            this.roomId = roomId;
            this.playlistItemId = playlistItemId;
            this.userId = userId;
            this.beatmapId = beatmapId;
            this.rulesetId = rulesetId;
            this.beatmapHash = beatmapHash;
            this.createdAtEpochMs = System.currentTimeMillis();
        }
    }
}
