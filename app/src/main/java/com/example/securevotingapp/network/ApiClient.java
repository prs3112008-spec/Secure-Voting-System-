package com.example.securevotingapp.network;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.securevotingapp.SecurePrefs;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {

    private static final String BASE_URL = "https://secure-voting-system-9rnd.onrender.com/";
    private static Retrofit retrofitInstance;
    private static VotingApiService apiService;

    public static synchronized VotingApiService getApiService(Context context) {
        if (apiService == null) {
            Context appContext = context.getApplicationContext();

            OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS);

            // Auth Interceptor attaching Authorization: Bearer <jwt_token>
            Interceptor authInterceptor = chain -> {
                Request originalRequest = chain.request();
                SharedPreferences prefs = SecurePrefs.get(appContext);
                String token = prefs.getString("jwt_token", null);

                if (token != null && !token.isEmpty()) {
                    Request authenticatedRequest = originalRequest.newBuilder()
                            .header("Authorization", "Bearer " + token)
                            .build();
                    return chain.proceed(authenticatedRequest);
                }
                return chain.proceed(originalRequest);
            };

            clientBuilder.addInterceptor(authInterceptor);

            HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
            clientBuilder.addInterceptor(loggingInterceptor);

            retrofitInstance = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(clientBuilder.build())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();

            apiService = retrofitInstance.create(VotingApiService.class);
        }
        return apiService;
    }

    public static String parseErrorMessage(retrofit2.Response<?> response, String fallback) {
        if (response != null && response.errorBody() != null) {
            try {
                String errJson = response.errorBody().string();
                JsonObject obj = JsonParser.parseString(errJson).getAsJsonObject();
                if (obj.has("error")) {
                    return obj.get("error").getAsString();
                } else if (obj.has("message")) {
                    return obj.get("message").getAsString();
                }
            } catch (Exception ignored) {}
        }
        return fallback;
    }
}