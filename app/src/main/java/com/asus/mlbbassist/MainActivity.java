package com.asus.mlbbassist;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OVERLAY_PERMISSION = 1001;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1002;
    private static final int REQUEST_MEDIA_PROJECTION = 1003;

    private TextInputEditText etUsername;
    private TextInputEditText etKey1, etKey2, etKey3;
    private MaterialAutoCompleteTextView spinnerModel;
    private MaterialAutoCompleteTextView spinnerKeySelection;
    private TextView tvStatus;
    private KeyManager keyManager;
    private android.media.projection.MediaProjectionManager mediaProjectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        keyManager = new KeyManager(this);

        etUsername = findViewById(R.id.etUsername);
        etKey1 = findViewById(R.id.etKey1);
        etKey2 = findViewById(R.id.etKey2);
        etKey3 = findViewById(R.id.etKey3);
        spinnerModel = findViewById(R.id.spinnerModel);
        spinnerKeySelection = findViewById(R.id.spinnerKeySelection);
        tvStatus = findViewById(R.id.tvStatus);
        Button btnTestKeys = findViewById(R.id.btnTestKeys);
        Button btnToggleOverlay = findViewById(R.id.btnStartOverlay);
        Button btnToggleApi = findViewById(R.id.btnToggleApi);
        android.view.View layoutApiConfig = findViewById(R.id.layoutApiConfig);

        setupSpinner();
        loadSavedData();

        btnTestKeys.setOnClickListener(v -> testKeys());
        btnToggleOverlay.setOnClickListener(v -> toggleOverlay());
        btnToggleOverlay.setText(MlbbOverlayService.isServiceRunning ? "Stop Overlay" : "Start Overlay");

        btnToggleApi.setOnClickListener(v -> {
            if (layoutApiConfig.getVisibility() == android.view.View.VISIBLE) {
                layoutApiConfig.setVisibility(android.view.View.GONE);
                btnToggleApi.setText("Show API Configuration");
            } else {
                layoutApiConfig.setVisibility(android.view.View.VISIBLE);
                btnToggleApi.setText("Hide API Configuration");
            }
        });
    }

    private void setupSpinner() {
        String[] models = {
                "gemini-3-flash-preview",
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-2.0-flash",
                "gemini-2.0-flash-exp",
                "gemini-1.5-flash",
                "gemini-1.5-pro"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, models);
        spinnerModel.setAdapter(adapter);

        String[] keys = { "Key 1", "Key 2", "Key 3" };
        ArrayAdapter<String> keyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, keys);
        spinnerKeySelection.setAdapter(keyAdapter);
    }

    private void loadSavedData() {
        etUsername.setText(keyManager.getUsername());
        etKey1.setText(keyManager.getKey(0));
        etKey2.setText(keyManager.getKey(1));
        etKey3.setText(keyManager.getKey(2));

        String savedModel = keyManager.getModel();
        spinnerModel.setText(savedModel, false);

        int activeIndex = keyManager.getActiveKeyIndex();
        String[] keys = { "Key 1", "Key 2", "Key 3" };
        if (activeIndex >= 0 && activeIndex < keys.length) {
            spinnerKeySelection.setText(keys[activeIndex], false);
        }

        updateStatusLabel();
    }

    private void saveData() {
        keyManager.saveUsername(etUsername.getText().toString());
        keyManager.saveKeys(
                etKey1.getText().toString(),
                etKey2.getText().toString(),
                etKey3.getText().toString());
        keyManager.saveModel(spinnerModel.getText().toString());

        String selectedKey = spinnerKeySelection.getText().toString();
        int index = 0;
        if (selectedKey.equals("Key 2"))
            index = 1;
        else if (selectedKey.equals("Key 3"))
            index = 2;
        keyManager.saveActiveKeyIndex(index);
    }

    private void updateStatusLabel() {
        tvStatus.setText("Active Key Index: " + keyManager.getActiveKeyIndex());
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Button btnToggleOverlay = findViewById(R.id.btnStartOverlay);
        btnToggleOverlay.setText(MlbbOverlayService.isServiceRunning ? "Stop Overlay" : "Start Overlay");
    }

    private void testKeys() {
        saveData();
        if (!keyManager.areKeysSet()) {
            Toast.makeText(this, "Please enter at least one API key", Toast.LENGTH_SHORT).show();
            return;
        }

        tvStatus.setText("Validating API Key...");

        GeminiHelper helper = new GeminiHelper(keyManager);
        helper.validateKey(new GeminiHelper.Callback() {
            @Override
            public void onSuccess(String result) {
                runOnUiThread(() -> {
                    tvStatus.setText(result);
                    Toast.makeText(MainActivity.this, result, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error, boolean isRateLimit) {
                runOnUiThread(() -> {
                    tvStatus.setText("Validation Failed: " + error);
                    Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void toggleOverlay() {
        saveData();
        if (MlbbOverlayService.isServiceRunning) {
            stopOverlayService();
        } else {
            checkPermissionsAndStart();
        }
    }

    private void stopOverlayService() {
        Intent intent = new Intent(this, MlbbOverlayService.class);
        stopService(intent);
        MlbbOverlayService.isServiceRunning = false; // Immediate update
        Button btnToggleOverlay = findViewById(R.id.btnStartOverlay);
        btnToggleOverlay.setText("Start Overlay");
        Toast.makeText(this, "Overlay Stopped", Toast.LENGTH_SHORT).show();
    }

    private void checkPermissionsAndStart() {
        saveData();
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[] { android.Manifest.permission.POST_NOTIFICATIONS },
                        REQUEST_NOTIFICATION_PERMISSION);
                return;
            }
        }

        requestScreenCapture();
    }

    private void requestScreenCapture() {
        mediaProjectionManager = (android.media.projection.MediaProjectionManager) getSystemService(
                MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
    }

    private void startOverlayService(int resultCode, Intent data) {
        Intent intent = new Intent(this, MlbbOverlayService.class);
        intent.putExtra("RESULT_CODE", resultCode);
        intent.putExtra("DATA", data);
        startForegroundService(intent);
        MlbbOverlayService.isServiceRunning = true; // Immediate update
        Button btnToggleOverlay = findViewById(R.id.btnStartOverlay);
        btnToggleOverlay.setText("Stop Overlay");
        Toast.makeText(this, "Overlay Started", Toast.LENGTH_SHORT).show();
        // finish(); // Kept open to allow toggling
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                checkPermissionsAndStart();
            } else {
                Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK) {
                startOverlayService(resultCode, data);
            } else {
                Toast.makeText(this, "Screen capture permission is required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            requestScreenCapture();
        }
    }
}
