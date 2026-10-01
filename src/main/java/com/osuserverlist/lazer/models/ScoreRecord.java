package com.osuserverlist.lazer.models;

public class ScoreRecord {
    public long id;
    public String mapMd5;
    public long score;
    public long lazerScore;
    public float pp;
    public float acc;
    public int maxCombo;
    public int mods;
    public int n300;
    public int n100;
    public int n50;
    public int nmiss;
    public int ngeki;
    public int nkatu;
    public String grade = "N";
    public int status;
    public int mode;
    public long playTimeEpochSec;
    public int timeElapsed;
    public boolean perfect;
    public int userId;
    public String username = "";
    public String country = "XX";

    // Beatmap fields
    public int mapId;
    public int setId;
    public int mapStatus;
    public String artist = "";
    public String title = "";
    public String version = "";
    public String creator = "";
    public int totalLength;
    public float diff;
    public float bpm;
    public float cs;
    public float ar;
    public float od;
    public float hp;
    public int mapMaxCombo;
}
