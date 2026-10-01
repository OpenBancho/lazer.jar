package com.osuserverlist.lazer.models;

public class User {
    public int id;
    public String name;
    public String safeName;
    public String email;
    public int privileges;
    public String passwordHash;
    public String country = "xx";
    public int silenceEnd = 0;
    public int donorEnd = 0;
    public int creationTime = 0;
    public int latestActivity = 0;
    public int preferredMode = 0;
    public String customBadgeName;
    public String customBadgeIcon;
    public String customBanner;
}
