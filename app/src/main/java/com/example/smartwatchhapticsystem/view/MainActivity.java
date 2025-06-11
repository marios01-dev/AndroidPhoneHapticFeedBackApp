package com.example.smartwatchhapticsystem.view;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.smartwatchhapticsystem.R;


public class MainActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 101;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 102;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestNotificationPermission(); // Request notification permission at startup

    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST_CODE);
            } else {
                // Already granted → next
                requestLocationPermissions();
            }
        } else {
            // Not required below Android 13 → skip to next
            requestLocationPermissions();
        }
    }


    /**
     * **Check and Request Bluetooth Permissions**
     */
    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                            != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                        },
                        BLUETOOTH_PERMISSION_REQUEST_CODE);
            } else {
                onAllPermissionsGranted(); // All done
            }
        } else {
            onAllPermissionsGranted(); // Not required before Android 12
        }
    }

    private void requestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            requestBluetoothPermissions(); // Already granted → next
        }
    }

    private void onAllPermissionsGranted() {
        Log.d("Permissions", "✅ All permissions granted or handled. Starting MonitoringService...");

        Intent serviceIntent = new Intent(this, MonitoringService.class);
        startForegroundService(serviceIntent);  // Required for Android 8+
    }

    /**
     * **Handle Permission Requests**
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            // Continue regardless of result
            requestLocationPermissions();

        } else if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            requestBluetoothPermissions();

        } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            onAllPermissionsGranted(); // Done
        }
    }
    /**
     * **Clean up resources on activity destruction**
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();

    }
}
