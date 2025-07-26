package com.example.smartwatchhapticsystem.view;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import com.example.smartwatchhapticsystem.R;
import com.example.smartwatchhapticsystem.controller.BluetoothConnectionManager;
import com.example.smartwatchhapticsystem.controller.LocationController;
import com.example.smartwatchhapticsystem.controller.NetworkController;
import com.example.smartwatchhapticsystem.model.LocationData;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.Priority;
import java.util.Map;

public class MonitoringService extends Service {
    private static final String CHANNEL_ID = "monitoring_service_channel";
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final int RETRY_INTERVAL_MS = 3000; // 3 seconds
    private static final int RETRY_DELAY_MS = 500; // Retry every 5 seconds

    private static final int MAX_RETRIES = 8;       // Optional: set to -1 for unlimited
    private int currentRetries = 0;
    private LocationController locationController;
    private NetworkController networkController;
    private BluetoothConnectionManager bluetoothManager;
    private static final String TAG = "MainActivity";
    private String monitoringType = "";
    private String identifier = "Android-50"; // Example : Android-42

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("MonitoringService", "🚀 Service created");

        bluetoothManager = new BluetoothConnectionManager(this, identifier);
        networkController = new NetworkController(this, bluetoothManager);
        locationController = new LocationController(this);

        startForegroundWithNotification();
        getMonitoringTypeFromNodeRED();  // Starts the core monitoring logic

    }

    // This method sets up and starts the foreground notification required for foreground services
    private void startForegroundWithNotification() {

        // Step 1: If running on Android 8.0 (API 26) or above, create a notification channel
        // Create a NotificationChannel with a unique ID, a user-visible name, and importance level
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,                                // Channel ID (used in Builder)
                "Monitoring Service Channel",               // Name shown in system settings
                NotificationManager.IMPORTANCE_LOW          // Importance level (no sound, still visible)
        );

        // Get the system service responsible for managing notifications
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            // Register the channel with the system (only needed once)
            manager.createNotificationChannel(channel);
        }

        // Step 2: Build the actual notification that will be shown to the user
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Smartwatch Monitoring")        // Title of the notification
                .setContentText("Running in background...")      // Subtext/description
                .setSmallIcon(R.drawable.ic_launcher_foreground) // Small icon in status bar
                .build();

        // Step 3: Promote this service to foreground status by showing the notification
        startForeground(1, notification);  // Must be called within 5 seconds of starting the service
    }

    /**
     * Retrieves the current monitoring type from the Node-RED backend and triggers the appropriate action
     * (e.g., heart rate or sun azimuth monitoring). Implements retry logic in case of network failure.
     */
    private void getMonitoringTypeFromNodeRED() {
        // Step 1: Ensure the network controller is initialized
        if (networkController != null) {

            // Step 2: Call the backend to fetch the monitoring type
            networkController.getMonitoringType(new NetworkController.OnMonitoringTypeReceived() {

                // Callback triggered when monitoring type is successfully retrieved
                @Override
                public void onReceived(String type) {
                    monitoringType = type;
                    currentRetries = 0;  // Reset retry counter on success

                    try {
                        Log.d(TAG, "📡 Received monitoringType: " + monitoringType);

                        // Step 3: Handle SunAzimuth monitoring
                        if ("SunAzimuth".equals(monitoringType) || "MoonAzimuth".equals(monitoringType)) {
                            if (checkLocationPermissions()) {
                                Log.d(TAG, "🔁 Permissions granted. Connecting for SunAzimuth or MoonAzimuth...");
                                connectToSmartwatchForMonitoring(monitoringType);
                                startLocationUpdates();  // Start location tracking for sun position or moon position
                            } else {
                                Log.w(TAG, "⚠️ Permissions not granted...");
                                // Optional: You could request location permissions here if needed
                                // requestLocationPermissions();
                            }

                            // Step 4: Handle HeartRate monitoring
                        } else if ("HeartRate".equals(monitoringType)) {
                            Log.d(TAG, "🔁 Connecting for HeartRate...");
                            connectToSmartwatchForMonitoring(monitoringType);

                            // Step 5: Handle unknown types
                        } else {
                            Log.w(TAG, "⚠️ Unknown monitoringType received: " + monitoringType);
                        }

                    } catch (Exception e) {
                        // Catch unexpected errors to prevent service crash
                        Log.e(TAG, "❌ Exception in getMonitoringTypeFromNodeRED() for type: " + monitoringType, e);
                    }
                }

                // Callback triggered when there is a network or server error
                @Override
                public void onError(String errorMessage) {
                    Log.e(TAG, "❌ Network error: " + errorMessage);

                    // Step 6: Retry with exponential backoff (or max retry limit)
                    if (MAX_RETRIES == -1 || currentRetries < MAX_RETRIES) {
                        currentRetries++;
                        Log.w(TAG, "🔁 Retrying to fetch monitoring type (attempt " + currentRetries + ") in " + (RETRY_DELAY_MS / 1000) + "s...");
                        retryHandler.postDelayed(() -> getMonitoringTypeFromNodeRED(), RETRY_DELAY_MS);
                    } else {
                        // Max retry limit reached — abort and notify user
                        Log.e(TAG, "🛑 Max retry limit reached. Aborting monitoring type fetch.");
                        Toast.makeText(getApplicationContext(), "Error: " + errorMessage + "\nMax retries reached.", Toast.LENGTH_LONG).show();
                    }
                }
            });

        } else {
            // Handle the case where the network controller is not initialized (should never happen)
            Log.e(TAG, "❌ networkController is null!");
        }
    }


    private boolean checkLocationPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }


    /**
     * Starts location updates using FusedLocationProviderClient.
     * Sends location data (lat/lon + IDs) to Node-RED on each update.
     */
    private void startLocationUpdates() {

        // Step 1: Build a high-accuracy location request
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30000) // 3s update interval
                .setMinUpdateIntervalMillis(5000) // Minimum interval: 5s (if another location is available sooner)
                .build();

        // Step 2: Start receiving location updates with a custom listener
        locationController.startLocationUpdates(locationRequest, new LocationController.OnLocationReceived() {

            // Callback for when a new location is received
            @Override
            public void onLocationReceived(Location location) {
                double lat = location.getLatitude();
                double lon = location.getLongitude();
                System.out.println("Updated Location: Lat=" + lat + ", Lon=" + lon);

                // Step 3: Build a data object that includes lat/lon and device/user IDs
                LocationData locationData = buildLocationDataWithIDs(lat, lon);

                // Step 4: Send the location data to Node-RED backend
                networkController.sendLocation(locationData, getApplicationContext(),monitoringType);
            }

            // Callback for handling errors in location updates
            @Override
            public void onError(String errorMessage) {
                System.out.println("Location Error: " + errorMessage);
            }
        });
    }


    /**
     * Builds a LocationData object that includes latitude, longitude, user ID, smartwatch ID, and Android device ID.
     * Attempts to recover any unknown values using system or Bluetooth information.
     *
     * @param latitude  The current latitude
     * @param longitude The current longitude
     * @return A fully populated LocationData object
     */
    private LocationData buildLocationDataWithIDs(double latitude, double longitude) {
        String androidId = "UnknownAndroid";
        String userId = "UnknownUser";
        String smartWatchId = "UnknownWatch";

        // Step 1: Try to extract Android ID from the system device name (e.g., "Android-1234")
//        String systemName = Settings.Global.getString(this.getContentResolver(), "device_name");
        String systemName = identifier;
        if (systemName != null && systemName.matches("^Android-\\d+$")) {
            androidId = systemName.split("-")[1]; // Extract only the number
            Log.d(TAG, "✅ Android ID from system name: " + androidId);
        } else {
            Log.w(TAG, "⚠️ Invalid Android device name format: " + systemName);
        }

        // Step 2: Try to extract WatchID and UserID from Bluetooth device alias (e.g., "UserID-123-SmartWatchID-456")
        try {
            if (bluetoothManager != null && bluetoothManager.getBluetoothSocket() != null) {
                BluetoothDevice device = bluetoothManager.getBluetoothSocket().getRemoteDevice();
                @SuppressLint("MissingPermission") String alias = device.getAlias(); // Only available on API 30+

                if (alias != null && alias.matches("^UserID-\\d+-SmartWatchID-\\d+$")) {
                    String[] tokens = alias.split("-");
                    userId = tokens[1];        // Extract "123" from "UserID-123"
                    smartWatchId = tokens[3];  // Extract "456" from "SmartWatchID-456"
                    Log.d(TAG, "✅ Parsed Watch alias: userId=" + userId + ", smartWatchId=" + smartWatchId);
                } else {
                    Log.w(TAG, "⚠️ Invalid watch alias format: " + alias);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to retrieve watch alias", e);
        }

        // Step 3: Return the constructed LocationData object
        return new LocationData(latitude, longitude, userId, smartWatchId, androidId);
    }


    /**
     * Attempts to connect to a paired smartwatch using Bluetooth and start heart rate monitoring.
     * If a watch is found, it establishes a connection and listens for heart rate data.
     * If no watch is found, or an error occurs, it retries after a delay.
     *
     * @param monitoringType The type of monitoring to activate (e.g., "HeartRate").
     */
    private void connectToSmartwatchForMonitoring(String monitoringType) {
        // Step 1: Attempt to get a connected smartwatch device
        BluetoothDevice smartwatch = bluetoothManager.getConnectedDevice();

        // If no device is found, retry after a delay
        if (smartwatch == null) {
            Log.e(TAG, "❌ No smartwatch found! Retrying in 3 seconds...");
            retryHandler.postDelayed(() -> connectToSmartwatchForMonitoring(monitoringType), RETRY_INTERVAL_MS);
            return;
        }

        // Step 2: Connect to the smartwatch and define how to handle incoming data or errors
        bluetoothManager.connectToWatch(smartwatch, new BluetoothConnectionManager.OnHeartRateReceived() {

            // Callback triggered when valid heart rate data is received
            @Override
            public void onReceived(Map<String, String> data) {
                Log.d(TAG, "✅ Full data received: " + data.toString());

                // Step 3: Forward heart rate data to Node-RED (if monitoring type matches)
                if ("HeartRate".equalsIgnoreCase(monitoringType)) {
                    sendHeartRateToNodeRed(data);
                }
                // Optional: You could handle other monitoring types here
                // e.g., else if ("Temperature".equalsIgnoreCase(monitoringType)) { ... }

                // Step 4: Stop retry attempts now that communication is successful
                retryHandler.removeCallbacksAndMessages(null);
            }

            // Callback triggered when an error occurs during connection or data reading
            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, errorMessage + " Retrying in 3 seconds...");
                // Retry connection after a delay
                retryHandler.postDelayed(() -> connectToSmartwatchForMonitoring(monitoringType), RETRY_INTERVAL_MS);
            }
        }, monitoringType);  // Pass the monitoring type to the connection method
    }

    /**
     * Sends parsed heart rate data to the Node-RED server using the network controller.
     *
     * @param data A map containing parsed data (e.g., heart rate value, user/device IDs).
     */
    private void sendHeartRateToNodeRed(Map<String, String> data) {
        networkController.sendHeartRateToNodeRed(data);
    }


    /**
     * Called when the system starts the service using startService() or startForegroundService().
     *
     * This is where you can initialize runtime logic or process data passed via the intent.
     * Returning START_STICKY means the service will automatically restart if it's killed by the system.
     *
     * @param intent  The Intent supplied to startService().
     * @param flags   Additional data about the start request.
     * @param startId A unique integer representing this specific request to start.
     * @return START_STICKY to request that the service be restarted if terminated.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("MonitoringService", "🟢 Service started");

        // Future: Retrieve extra data from intent if needed, e.g.:
        // String userId = intent.getStringExtra("UserID");

        // START_STICKY tells Android: "Restart this service if the system kills it."
        // Useful for background services that should keep running (e.g., monitoring)
        return START_STICKY;
    }

    /**
     * Called when the service is being destroyed, either by the system or manually via stopService().
     *
     * This is where you clean up any ongoing tasks, threads, or resources to avoid memory leaks.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d("MonitoringService", "🔴 Service stopped");

        // Step 1: Stop any pending retries for reconnecting or polling
        retryHandler.removeCallbacksAndMessages(null);

        // Step 2: Disconnect from the smartwatch if connected
        if (bluetoothManager != null) {
            bluetoothManager.disconnect();
        }

        // Step 3: Stop location updates to save battery and resources
        if (locationController != null) {
            locationController.stopLocationUpdates();
        }

        // Any additional cleanup (e.g., closing database, stopping sensors) can go here
    }

    /**
     * Called when the app's task is removed from the "Recent Apps" screen (i.e., swiped away).
     * This is your opportunity to perform final cleanup before the system kills the process.
     *
     * This is especially useful for foreground/background services to stop them gracefully
     * when the user force-closes the app.
     *
     * @param rootIntent The original intent that was used to start the task (not usually needed here).
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d("MonitoringService", "🛑 App closed from Resents. Stopping service.");

        // Step 1: Cancel any pending retries or scheduled background tasks
        retryHandler.removeCallbacksAndMessages(null);

        // Step 2: Disconnect from the smartwatch (if connected)
        if (bluetoothManager != null) {
            bluetoothManager.disconnect();
        }

        // Step 3: Stop location tracking to avoid wasting battery/resources
        if (locationController != null) {
            locationController.stopLocationUpdates();
        }

        // Step 4: Stop the service completely (releases system resources and shuts it down)
        stopSelf();

        // Step 5: Call superclass to ensure proper system-level handling
        super.onTaskRemoved(rootIntent);
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
