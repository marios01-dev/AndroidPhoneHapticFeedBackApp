package com.example.smartwatchhapticsystem.model;

public class LocationData {
    private double lat;
    private double lon;
    private String userId;
    private String smartWatchId;
    private String androidId;
    public LocationData(double latitude, double longitude, String userId, String smartWatchId, String androidId) {
        this.lat = latitude;
        this.lon = longitude;
        this.userId = userId;
        this.smartWatchId = smartWatchId;
        this.androidId = androidId;
    }
    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLon() {
        return lon;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public String getUserId() {
        return userId;
    }

    public String getSmartWatchId() {
        return smartWatchId;
    }

    public String getAndroidId() {
        return androidId;
    }
}
