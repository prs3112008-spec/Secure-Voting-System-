package com.example.securevotingapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

public class SecurePrefs {

    private static final String TAG = "SecurePrefs";
    private static final String ENCRYPTED_PREFS_NAME = "VotingPrefs_Encrypted";
    private static final String LEGACY_PREFS_NAME = "VotingPrefs";

    private static SharedPreferences instance;

    public static synchronized SharedPreferences get(Context context) {
        if (instance == null) {
            try {
                Context appContext = context.getApplicationContext();
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);

                instance = EncryptedSharedPreferences.create(
                        ENCRYPTED_PREFS_NAME,
                        masterKeyAlias,
                        appContext,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.values()[0],
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.values()[0]
                );

                // Safe migration/cleanup: If old plaintext preferences file exists, clear it
                SharedPreferences legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
                if (legacyPrefs.contains("jwt_token")) {
                    legacyPrefs.edit().clear().apply();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, falling back to standard preferences", e);
                instance = context.getApplicationContext().getSharedPreferences(ENCRYPTED_PREFS_NAME, Context.MODE_PRIVATE);
            }
        }
        return instance;
    }

    public static void clear(Context context) {
        SharedPreferences prefs = get(context);
        prefs.edit().clear().apply();

        try {
            SharedPreferences legacyPrefs = context.getApplicationContext().getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
            legacyPrefs.edit().clear().apply();
        } catch (Exception ignored) {}
    }
}