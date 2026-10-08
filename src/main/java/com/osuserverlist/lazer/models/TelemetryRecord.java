package com.osuserverlist.lazer.models;

import java.sql.Timestamp;

public class TelemetryRecord {
    public long id;
    public int userId;
    public String username;
    public String ip;
    public String systemFingerprint;
    public String hardwareHash;
    public String os;
    public String lazerVersion;
    public String arch;
    public String cpu;
    public int cores;
    public String gpu;
    public int ramMb;
    public String resolution;
    public int refreshRate;
    public String locale;
    public String timezone;
    public String rawJson;
    public Timestamp createdAt;
}
