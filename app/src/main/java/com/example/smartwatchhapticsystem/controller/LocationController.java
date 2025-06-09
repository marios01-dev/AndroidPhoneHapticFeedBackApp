package com.example.smartwatchhapticsystem.controller;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

public class LocationController {
    private final FusedLocationProviderClient locationProvider;
    private LocationCallback locationCallback;

    public LocationController(Context context) {
        locationProvider = LocationServices.getFusedLocationProviderClient(context);
    }

    /**
     * Fetch the last known location (one-time retrieval).
     */
    @SuppressLint("MissingPermission")
    public void fetchLastLocation(OnLocationReceived listener) {
        locationProvider.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                listener.onLocationReceived(location);
            } else {
                listener.onError("Last location is unavailable.");
            }
        }).addOnFailureListener(e -> listener.onError(e.getMessage()));
    }

    /**
     * Start continuous location updates.
     */
    @SuppressLint("MissingPermission")
    public void startLocationUpdates(LocationRequest locationRequest, OnLocationReceived listener) {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {


                if (locationResult != null && !locationResult.getLocations().isEmpty()) {
                    // Find the most accurate location
                    Location mostAccurateLocation = null;
                    for (Location location : locationResult.getLocations()) {
                        if (mostAccurateLocation == null ||
                                location.getAccuracy() < mostAccurateLocation.getAccuracy()) {
                            mostAccurateLocation = location;
                        }
                    }

                    // If a valid location is found, pass it to the listener
                    if (mostAccurateLocation != null) {
                        listener.onLocationReceived(mostAccurateLocation);
                    }
                }
            }
        };

        locationProvider.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }


    /**
     * Stop continuous location updates.
     */
    public void stopLocationUpdates() {
        if (locationCallback != null) {
            locationProvider.removeLocationUpdates(locationCallback);
        }
    }

    /**
     * Interface for location callbacks.
     */
    public interface OnLocationReceived {
        void onLocationReceived(Location location);

        void onError(String errorMessage);
    }
}
