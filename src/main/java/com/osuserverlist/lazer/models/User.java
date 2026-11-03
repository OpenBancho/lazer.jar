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
    /** Path of the page background a supporter uploaded on the website, e.g. /api/v1/background/1.jpg. */
    public String customBackground;
    /** Accent colour of the profile as #rrggbb. */
    public String profileColor;
    /** Colour of the profile's panels as #rrggbb. */
    public String profileColorSecondary;
}
