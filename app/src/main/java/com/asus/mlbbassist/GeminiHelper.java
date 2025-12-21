package com.asus.mlbbassist;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class GeminiHelper {
    private final KeyManager keyManager;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onSuccess(String result);

        void onError(String error, boolean isRateLimit);
    }

    public GeminiHelper(KeyManager keyManager) {
        this.keyManager = keyManager;
    }

    public void analyzeImage(Bitmap bitmap, Callback callback) {
        performAnalysis(bitmap, callback, 0);
    }

    public void validateKey(Callback callback) {
        String key = keyManager.getActiveKey();
        String modelName = keyManager.getModel();

        if (key.isEmpty()) {
            callback.onError("No API key set.", false);
            return;
        }

        GenerativeModel gm = new GenerativeModel(modelName, key);
        GenerativeModelFutures model = GenerativeModelFutures.from(gm);

        Content content = new Content.Builder()
                .addText("Hello")
                .build();

        ListenableFuture<GenerateContentResponse> response = model.generateContent(content);

        Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                callback.onSuccess("Connection Successful");
            }

            @Override
            public void onFailure(Throwable t) {
                callback.onError(t.getMessage(), false);
            }
        }, executor);
    }

    private void performAnalysis(Bitmap bitmap, Callback callback, int retryCount) {
        String key = keyManager.getActiveKey();
        String modelName = keyManager.getModel();

        // Skip empty keys and rotate if needed
        if (key.isEmpty()) {
            keyManager.rotateKey();
            if (retryCount < 3) {
                performAnalysis(bitmap, callback, retryCount + 1);
            } else {
                callback.onError("No valid API keys found.", false);
            }
            return;
        }

        GenerativeModel gm = new GenerativeModel(modelName, key);
        GenerativeModelFutures model = GenerativeModelFutures.from(gm);

        String username = keyManager.getUsername();
        String prompt = "You are an expert Mobile Legends: Bang Bang (MLBB) coach.\n" +
                "First, check if this image is a valid in-game scoreboard (showing players, items, KDA).\n" +
                "If it is NOT a scoreboard, output EXACTLY this string: 'INVALID_IMAGE'.\n" +
                "If it IS a valid scoreboard, proceed with the following analysis:\n" +
                "1. Identify the user's hero. "
                + (username.isEmpty() ? "Look for the gold-highlighted row or 'YOU' indicator."
                        : "Username: '" + username + "'.")
                + "\n" +
                "2. Analyze the enemy team composition and economy.\n" +
                "3. Suggest 2 crucial counter-items and briefly explain why.\n" +
                "Output strictly in json format:\n" +
                "{\"player_status\": {\"hero_name\": \"string\", \"kda\": \"string\"}, \"top_threats\": [\"Enemy Hero 1\", \"Enemy Hero 2\"], \"recommended_build\": {\"items\": [\"Item 1\", \"Item 2\"], \"reasoning\": \"string\"}, \"teamfight_strategy\": \"string\"}";

        Content content = new Content.Builder()
                .addImage(bitmap)
                .addText(prompt)
                .build();

        ListenableFuture<GenerateContentResponse> response = model.generateContent(content);

        Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                String responseText = result.getText();
                Log.d("GeminiHelper", "API Response: " + responseText);
                callback.onSuccess(responseText);
            }

            @Override
            public void onFailure(Throwable t) {
                String error = t.getMessage();
                boolean isRateLimit = error != null && error.contains("429");

                if (isRateLimit) {
                    keyManager.rotateKey();
                    if (retryCount < 3) {
                        // Retry with new key
                        performAnalysis(bitmap, callback, retryCount + 1);
                    } else {
                        callback.onError("All keys exhausted or rate limited.", true);
                    }
                } else {
                    callback.onError(t.getMessage(), false);
                }
            }
        }, executor);
    }
}
