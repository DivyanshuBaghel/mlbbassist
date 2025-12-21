package com.asus.mlbbassist;

import android.content.Context;
import android.content.SharedPreferences;

public class KeyManager {
    private static final String PREF_NAME = "MlbbAssistPrefs";
    private static final String KEY_PREFIX = "gemini_key_";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_MODEL = "selected_model";
    private static final String KEY_ACTIVE_INDEX = "active_key_index";

    private final SharedPreferences prefs;
    private int currentKeyIndex = 0;

    public KeyManager(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.currentKeyIndex = prefs.getInt(KEY_ACTIVE_INDEX, 0);
    }

    public void saveKeys(String key1, String key2, String key3) {
        prefs.edit()
                .putString(KEY_PREFIX + "0", key1)
                .putString(KEY_PREFIX + "1", key2)
                .putString(KEY_PREFIX + "2", key3)
                .apply();
    }

    public void saveUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public void saveModel(String model) {
        prefs.edit().putString(KEY_MODEL, model).apply();
    }

    public String getModel() {
        return prefs.getString(KEY_MODEL, "gemini-2.5-flash");
    }

    public String getKey(int index) {
        return prefs.getString(KEY_PREFIX + index, "");
    }

    public String getActiveKey() {
        return getKey(currentKeyIndex);
    }

    public int getActiveKeyIndex() {
        return currentKeyIndex;
    }

    public void rotateKey() {
        currentKeyIndex = (currentKeyIndex + 1) % 3;
        saveActiveKeyIndex(currentKeyIndex);
    }

    public void saveActiveKeyIndex(int index) {
        if (index >= 0 && index < 3) {
            currentKeyIndex = index;
            prefs.edit().putInt(KEY_ACTIVE_INDEX, index).apply();
        }
    }

    public boolean areKeysSet() {
        return !getKey(0).isEmpty() || !getKey(1).isEmpty() || !getKey(2).isEmpty();
    }
}
