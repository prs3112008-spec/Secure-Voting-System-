package com.example.securevotingapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public class SecurePrefs {

    private static final String TAG = "SecurePrefs";
    private static final String ENCRYPTED_PREFS_NAME = "VotingPrefs_Encrypted";
    private static final String LEGACY_PREFS_NAME = "VotingPrefs";

    private static SharedPreferences instance;

    public static synchronized SharedPreferences get(Context context) {
        if (instance == null) {
            Context appContext = context.getApplicationContext();

            // 1. Synchronously purge any legacy plaintext preferences immediately
            purgeLegacyPrefs(appContext);

            try {
                MasterKey masterKey = new MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();

                instance = EncryptedSharedPreferences.create(
                        appContext,
                        ENCRYPTED_PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception e) {
                // 2. Fail closed: Do NOT silently downgrade to plaintext storage
                Log.e(TAG, "Hardware Keystore initialization failed; refusing plaintext fallback", e);
                throw new IllegalStateException("Secure storage unavailable", e);
            }
        }
        return instance;
    }

    public static synchronized void clear(Context context) {
        Context appContext = context.getApplicationContext();
        try {
            if (instance != null) {
                instance.edit().clear().commit();
            } else {
                appContext.deleteSharedPreferences(ENCRYPTED_PREFS_NAME);
            }
        } catch (Exception e) {
            // If Keystore is corrupted, delete the encrypted XML file completely so the user can re-login cleanly
            appContext.deleteSharedPreferences(ENCRYPTED_PREFS_NAME);
            instance = null;
        }
        purgeLegacyPrefs(appContext);
    }

    private static void purgeLegacyPrefs(Context appContext) {
        try {
            SharedPreferences legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
            if (!legacyPrefs.getAll().isEmpty()) {
                legacyPrefs.edit().clear().commit();
            }
            appContext.deleteSharedPreferences(LEGACY_PREFS_NAME);
        } catch (Exception ignored) {}
    }
}