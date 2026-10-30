package com.osuserverlist.lazer.anticheat;

public class ServerFencingToken {
    public enum State {
        PENDING,
        PROCESSING,
        SUBMITTED,
        REJECTED_EXHAUSTED,
        REJECTED_FATAL
    }

    public final long id;
    public final int userId;
    public final int beatmapId;
    public final int rulesetId;
    public final String beatmapHash;
    public final long createdAtEpochMs;

    public volatile State state = State.PENDING;
    public volatile long fencingEpoch = 0;
    public volatile long leaseExpirationEpochMs = 0;
    public volatile int failedAttempts = 0;
    public volatile byte[] sessionKey = null;
    public volatile String cachedResponseJson = null;

    public ServerFencingToken(long id, int userId, int beatmapId, int rulesetId, String beatmapHash) {
        this.id = id;
        this.userId = userId;
        this.beatmapId = beatmapId;
        this.rulesetId = rulesetId;
        this.beatmapHash = beatmapHash;
        this.createdAtEpochMs = System.currentTimeMillis();
    }
}
