package com.example.securevotingapp.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;

public interface VotingApiService {

    // Auth
    @POST("api/auth/login")
    Call<JsonObject> login(@Body JsonObject body);

    @POST("api/auth/register")
    Call<JsonObject> register(@Body JsonObject body);

    @POST("api/auth/register-face")
    Call<JsonObject> registerFace(@Body JsonObject body);

    @POST("api/auth/verify-face")
    Call<JsonObject> verifyFace(@Body JsonObject body);

    @POST("api/auth/reset-password")
    Call<JsonObject> resetPassword(@Body JsonObject body);

    // Polls
    @GET("api/polls")
    Call<JsonArray> getPolls();

    @POST("api/polls/create")
    Call<JsonObject> createPoll(@Body JsonObject body);

    @GET("api/polls/{pollId}/results")
    Call<JsonObject> getPollResults(@Path("pollId") int pollId);

    // Candidates
    @GET("api/candidates/{pollId}")
    Call<JsonArray> getCandidates(@Path("pollId") int pollId);

    @POST("api/candidates/add")
    Call<JsonObject> addCandidate(@Body JsonObject body);

    @DELETE("api/candidates/{id}")
    Call<JsonObject> deleteCandidate(@Path("id") int candidateId);

    // Votes
    @GET("api/votes/election-status/{pollId}")
    Call<JsonObject> getElectionStatus(@Path("pollId") int pollId);

    @GET("api/votes/status/{pollId}")
    Call<JsonObject> getVoteStatus(@Path("pollId") int pollId);

    @POST("api/votes/cast")
    Call<JsonObject> castVote(@Body JsonObject body);

    @GET("api/votes/verify/{hash}")
    Call<JsonObject> verifyVote(@Path("hash") String hash);

    @POST("api/votes/update-schedule")
    Call<JsonObject> updateSchedule(@Body JsonObject body);

    @GET("api/votes/verify-chain")
    Call<JsonObject> verifyChainIntegrity();

    // Admin
    @GET("api/admin/voters")
    Call<JsonArray> getVoters();

    @PUT("api/admin/voters/{id}/approve")
    Call<JsonObject> approveVoter(@Path("id") int userId, @Body JsonObject body);

    @GET("api/admin/audit-logs")
    Call<com.google.gson.JsonElement> getAuditLogs();

    @GET("api/admin/export-results/{pollId}")
    Call<ResponseBody> exportResults(@Path("pollId") int pollId);

    @POST("api/admin/delegate-role")
    Call<JsonObject> delegateRole(@Body JsonObject body);
}