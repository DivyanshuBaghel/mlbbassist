package com.asus.mlbbassist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import android.os.HandlerThread;

public class MlbbOverlayService extends Service {

    private WindowManager windowManager;
    private View overlayView;
    private View expandedView;
    private View layoutHeader;
    private View layoutThreats, layoutBuild, layoutStrategy;
    private ImageView btnFloating;
    private TextView tvHeroInfo, tvThreats, tvBuildItems, tvBuildReason, tvStrategy, tvStatusOverlay;
    private ImageView btnClose;
    private Button btnAnalyze;
    private ProgressBar progressBar;

    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;

    private GeminiHelper geminiHelper;
    private KeyManager keyManager;

    private HandlerThread imageThread;
    private Handler imageHandler;

    private int mResultCode = -1;
    private Intent mResultData;

    private MediaProjection.Callback mediaProjectionCallback;
    private volatile boolean isCaptureRequested = false;

    private static final String CHANNEL_ID = "OverlayServiceChannel";

    public static boolean isServiceRunning = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;
        createNotificationChannel();
        startForeground(1, createNotification());

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        geminiHelper = new GeminiHelper(new KeyManager(this));
        keyManager = new KeyManager(this);

        imageThread = new HandlerThread("ImageListener");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());

        mediaProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                if (mediaProjection != null) {
                    mediaProjection.unregisterCallback(mediaProjectionCallback);
                    mediaProjection = null;
                    virtualDisplay = null;
                }
            }
        };

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        // Ensure width/height are reasonable for capture to avoid OOM
        // Simple resizing if needed, but for now take full screen

        setupOverlay();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "Overlay Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("MLBB Assist Overlay")
                .setContentText("Tap overlay to analyze").setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent).build();
    }

    private void setupOverlay() {
        // Use a ContextThemeWrapper to ensure the Material Theme is applied
        android.view.ContextThemeWrapper contextThemeWrapper = new android.view.ContextThemeWrapper(this,
                R.style.Theme_MlbbAssist);
        overlayView = LayoutInflater.from(contextThemeWrapper).inflate(R.layout.overlay_layout, null);

        btnFloating = overlayView.findViewById(R.id.btnFloating);
        expandedView = overlayView.findViewById(R.id.layoutExpanded);
        layoutHeader = overlayView.findViewById(R.id.layoutHeader);
        tvStatusOverlay = overlayView.findViewById(R.id.tvStatusOverlay);

        // New UI Elements
        tvHeroInfo = overlayView.findViewById(R.id.tvHeroInfo);
        layoutThreats = overlayView.findViewById(R.id.layoutThreats);
        tvThreats = overlayView.findViewById(R.id.tvThreats);
        layoutBuild = overlayView.findViewById(R.id.layoutBuild);
        tvBuildItems = overlayView.findViewById(R.id.tvBuildItems);
        tvBuildReason = overlayView.findViewById(R.id.tvBuildReason);
        layoutStrategy = overlayView.findViewById(R.id.layoutStrategy);
        tvStrategy = overlayView.findViewById(R.id.tvStrategy);

        btnClose = overlayView.findViewById(R.id.btnClose);
        btnAnalyze = overlayView.findViewById(R.id.btnAnalyze);
        progressBar = overlayView.findViewById(R.id.progressBar);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        // Common drag listener
        View.OnTouchListener dragListener = new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(overlayView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (v == btnFloating) { // Only toggle if clicking floating button
                            int Xdiff = (int) (event.getRawX() - initialTouchX);
                            int Ydiff = (int) (event.getRawY() - initialTouchY);
                            if (Xdiff < 10 && Ydiff < 10) {
                                toggleView();
                            }
                        }
                        return true;
                }
                return false;
            }
        };

        btnFloating.setOnTouchListener(dragListener);
        layoutHeader.setOnTouchListener(dragListener);

        btnClose.setOnClickListener(v -> {
            expandedView.setVisibility(View.GONE);
            btnFloating.setVisibility(View.VISIBLE);
        });

        windowManager.addView(overlayView, params);

        btnAnalyze.setOnClickListener(v -> startAnalysis());
    }

    private void toggleView() {
        if (expandedView.getVisibility() == View.VISIBLE) {
            expandedView.setVisibility(View.GONE);
            btnFloating.setVisibility(View.VISIBLE);
        } else {
            expandedView.setVisibility(View.VISIBLE);
            btnFloating.setVisibility(View.GONE);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int resultCode = intent.getIntExtra("RESULT_CODE", 0);
            Intent data = intent.getParcelableExtra("DATA");

            if (resultCode == -1 && data != null) { // -1 is Activity.RESULT_OK
                mResultCode = resultCode;
                mResultData = data;

                mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                mediaProjection = mediaProjectionManager.getMediaProjection(mResultCode, mResultData);
                mediaProjection.registerCallback(mediaProjectionCallback, null);

                createVirtualDisplay();

                new Handler(Looper.getMainLooper())
                        .post(() -> Toast.makeText(this, "Overlay Service Ready", Toast.LENGTH_SHORT).show());
            } else {
                new Handler(Looper.getMainLooper()).post(
                        () -> Toast.makeText(this, "Overlay Error: Missing Permissions", Toast.LENGTH_LONG).show());
            }
        }
        return START_NOT_STICKY;
    }

    private void createVirtualDisplay() {
        // Initialize persistent capture
        if (imageReader != null) {
            imageReader.close();
        }
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    if (isCaptureRequested) {
                        processImage(image);
                        isCaptureRequested = false;
                    } else {
                        image.close();
                    }
                }
            } catch (Exception e) {
                if (image != null)
                    image.close();
                e.printStackTrace();
            }
        }, imageHandler);

        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture", screenWidth, screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
        } else {
            virtualDisplay.resize(screenWidth, screenHeight, screenDensity);
            virtualDisplay.setSurface(imageReader.getSurface());
        }
    }

    private void processImage(Image image) {
        // Restore visibility immediately
        new Handler(Looper.getMainLooper()).post(() -> {
            if (overlayView != null)
                overlayView.setVisibility(View.VISIBLE);
        });

        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight,
                    Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            image.close();

            // Clean up immediately
            // Do not close imageReader/virtualDisplay here (persistent)
            // cleanupCapture();

            // Smart Cropping: Keep center 80% width (cut 10% sides)
            // Vertical: Keep Top 90% (cut 10% from BOTTOM only)
            int marginX = (int) (screenWidth * 0.10f);
            int marginBottom = (int) (screenHeight * 0.10f);

            int startX = marginX;
            int startY = 0; // Top is kept
            int cropWidth = screenWidth - (marginX * 2);
            int cropHeight = screenHeight - marginBottom;

            Bitmap cleanBitmap = Bitmap.createBitmap(bitmap, startX, startY, cropWidth, cropHeight);

            // Debug: Save image to verify
            saveDebugBitmap(cleanBitmap);

            new Handler(Looper.getMainLooper()).post(() -> analyzeWithGemini(cleanBitmap));

        } catch (Exception e) {
            e.printStackTrace();
            if (image != null)
                image.close();
            // cleanupCapture();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (overlayView != null)
                    overlayView.setVisibility(View.VISIBLE); // Ensure visible on error
                tvStatusOverlay.setText("Error");
                tvHeroInfo.setText("Image processing failed.");
                tvStrategy.setText("-");
                progressBar.setVisibility(View.GONE);
                btnAnalyze.setEnabled(true);
            });
        }
    }

    private void saveDebugBitmap(Bitmap bitmap) {
        try {
            java.io.File file = new java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
                    "gemini_debug.png");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            android.util.Log.i("GeminiDebug", "Saved debug image to: " + file.getAbsolutePath());
            new Handler(Looper.getMainLooper())
                    .post(() -> Toast.makeText(this, "Debug saved: " + file.getName(), Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startAnalysis() {
        if (mediaProjection == null || virtualDisplay == null) {
            tvStatusOverlay.setText("Error");
            tvHeroInfo.setText("Screen capture service not ready. Restart app.");
            return;
        }

        tvStatusOverlay.setText("Capturing...");
        tvHeroInfo.setText("Analyzing screen...");

        layoutThreats.setVisibility(View.GONE);
        layoutBuild.setVisibility(View.GONE);
        layoutStrategy.setVisibility(View.GONE);

        progressBar.setVisibility(View.VISIBLE);
        btnAnalyze.setEnabled(false);

        // Hide overlay for capture
        overlayView.setVisibility(View.GONE);

        // Delay capture to allow UI to update
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // Always recreate VirtualDisplay to force a fresh frame capture
            createVirtualDisplay();
            isCaptureRequested = true;

            // Timeout to reset flag if capture fails
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isCaptureRequested) {
                    isCaptureRequested = false;
                    if (overlayView != null)
                        overlayView.setVisibility(View.VISIBLE); // Restore on timeout
                    tvStatusOverlay.setText("Error");
                    tvHeroInfo.setText("Capture timed out.");
                    progressBar.setVisibility(View.GONE);
                    btnAnalyze.setEnabled(true);
                }
            }, 3000);
        }, 300); // 300ms delay for hidden state to take effect
    }

    // cleanupCapture removed as we stay persistent

    private void analyzeWithGemini(Bitmap bitmap) {
        String activeKeyIndex = String.valueOf(keyManager.getActiveKeyIndex() + 1); // 1-based
                                                                                    // for
                                                                                    // UI
        String modelName = keyManager.getModel();
        tvStatusOverlay.setText("Key " + activeKeyIndex + " | " + modelName);

        geminiHelper.analyzeImage(bitmap, new GeminiHelper.Callback() {
            @Override
            public void onSuccess(String result, long duration) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    tvStatusOverlay.setText("Ready (" + duration + "ms)");

                    if (result.trim().contains("INVALID_IMAGE")) {
                        // Handle invalid image error
                        tvHeroInfo.setText("Invalid Image");
                        layoutStrategy.setVisibility(View.VISIBLE);
                        tvStrategy.setText("Please open the MLBB Scoreboard to analyze!");
                        layoutThreats.setVisibility(View.GONE);
                        layoutBuild.setVisibility(View.GONE);
                    } else {
                        parseAndDisplayResult(result);
                    }

                    progressBar.setVisibility(View.GONE);
                    btnAnalyze.setEnabled(true);
                });
            }

            @Override
            public void onError(String error, boolean isRateLimit) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    tvStatusOverlay.setText("Error");
                    layoutStrategy.setVisibility(View.VISIBLE);
                    tvStrategy.setText("Error: " + error + (isRateLimit ? " (Rate Limit Switched)" : ""));
                    tvHeroInfo.setText("Analysis Failed");
                    progressBar.setVisibility(View.GONE);
                    btnAnalyze.setEnabled(true);
                });
            }
        });
    }

    private void parseAndDisplayResult(String jsonString) {
        try {
            // valid json should start with { and end with }
            // remove markdown if present
            String cleanJson = jsonString.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.replace("```json", "").replace("```", "").trim();
            } else if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.replace("```", "").trim();
            }

            JSONObject json = new JSONObject(cleanJson);

            // 1. Hero Info
            JSONObject playerStatus = json.optJSONObject("player_status");
            if (playerStatus != null) {
                String hero = playerStatus.optString("hero_name", "Unknown");
                String kda = playerStatus.optString("kda", "-/-/-");
                tvHeroInfo.setText("Hero: " + hero + " | KDA: " + kda);
            }

            layoutThreats.setVisibility(View.VISIBLE);
            layoutBuild.setVisibility(View.VISIBLE);
            layoutStrategy.setVisibility(View.VISIBLE);

            // 2. Threats
            JSONArray threats = json.optJSONArray("top_threats");
            if (threats != null) {
                StringBuilder threatList = new StringBuilder();
                for (int i = 0; i < threats.length(); i++) {
                    if (i > 0)
                        threatList.append(", ");
                    threatList.append(threats.optString(i));
                }
                tvThreats.setText(threatList.toString());
            }

            // 3. Recommended Build
            // Handle both nested object (standard) and stringified JSON (user prompt quirk)
            JSONObject build = null;
            Object buildObj = json.opt("recommended_build");
            if (buildObj instanceof JSONObject) {
                build = (JSONObject) buildObj;
                android.util.Log.d("GeminiDebug", "Build is JSONObject: " + build.toString());
            } else if (buildObj instanceof String) {
                try {
                    build = new JSONObject((String) buildObj);
                    android.util.Log.d("GeminiDebug", "Build is String: " + build.toString());
                } catch (JSONException e) {
                    android.util.Log.e("GeminiDebug", "Failed to parse build string: " + buildObj);
                }
            } else {
                android.util.Log.e("GeminiDebug",
                        "Build is UNKNOWN type: " + (buildObj != null ? buildObj.getClass().getName() : "null"));
            }

            if (build != null) {
                StringBuilder fullBuildText = new StringBuilder();

                // Counter Items
                JSONArray counterItems = build.optJSONArray("counter_items");
                android.util.Log.d("GeminiDebug",
                        "Counter Items: " + (counterItems != null ? counterItems.toString() : "null"));

                if (counterItems != null && counterItems.length() > 0) {
                    fullBuildText.append("Counter: ");
                    for (int i = 0; i < counterItems.length(); i++) {
                        if (i > 0)
                            fullBuildText.append(", ");
                        fullBuildText.append(counterItems.optString(i));
                    }
                }

                // Damage Items
                String damageItem = build.optString("damage_items", "");
                android.util.Log.d("GeminiDebug", "Damage Item: " + damageItem);

                if (!damageItem.isEmpty()) {
                    if (fullBuildText.length() > 0)
                        fullBuildText.append("\n");
                    fullBuildText.append("Damage: ").append(damageItem);
                }

                tvBuildItems.setText(fullBuildText.toString());
                tvBuildReason.setText(build.optString("reasoning", ""));
            }

            // 4. Strategy
            tvStrategy.setText(json.optString("teamfight_strategy", "No strategy provided."));

        } catch (JSONException e) {
            e.printStackTrace();
            String errorMsg = "Error parsing AI response.\nRaw: " + jsonString;
            if (errorMsg.length() > 200)
                errorMsg = errorMsg.substring(0, 200) + "...";
            layoutStrategy.setVisibility(View.VISIBLE);
            tvStrategy.setText(errorMsg);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        if (overlayView != null)
            windowManager.removeView(overlayView);
        if (virtualDisplay != null)
            virtualDisplay.release();
        if (mediaProjection != null) {
            mediaProjection.unregisterCallback(mediaProjectionCallback);
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (imageThread != null)
            imageThread.quitSafely();
    }
}
