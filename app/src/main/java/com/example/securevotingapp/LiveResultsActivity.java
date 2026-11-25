package com.example.securevotingapp;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.securevotingapp.network.ApiClient;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LiveResultsActivity extends AppCompatActivity {

    private static final String TAG = "LiveResultsActivity";

    ProgressBar resultsProgressBar;
    List<JSONObject> resultsList = new ArrayList<>();
    private String electionStatus = "LIVE";

    private int turnoutVotesCast = -1;
    private int turnoutApprovedVoters = -1;
    private double turnoutPercentage = -1.0;
    private int pollId = 1;
    private String pollTitle = "";

    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout resultsSwipeRefresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        super.setContentView(R.layout.activity_live_results);

        resultsSwipeRefresh = findViewById(R.id.resultsSwipeRefresh);
        if (resultsSwipeRefresh != null) {
            resultsSwipeRefresh.setOnRefreshListener(() -> {
                fetchElectionStatus();
                fetchLiveResults();
            });
        }

        pollId = getIntent().getIntExtra("EXTRA_POLL_ID", 1);
        pollTitle = getIntent().getStringExtra("EXTRA_POLL_TITLE");

        if (pollTitle != null && !pollTitle.isEmpty()) {
            TextView titleView = findViewById(R.id.liveResultsTitle);
            if (titleView != null) {
                titleView.setText("Poll #" + pollId + ": " + pollTitle);
            }
        }

        resultsProgressBar = findViewById(R.id.resultsProgressBar);

        fetchElectionStatus();
        fetchLiveResults();
    }

    private void fetchElectionStatus() {
        ApiClient.getApiService(this).getElectionStatus(pollId).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject obj = response.body();
                    electionStatus = obj.has("status") ? obj.get("status").getAsString() : "LIVE";
                    renderResults();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Log.e(TAG, "Error fetching election status", t);
            }
        });
    }

    private void fetchLiveResults() {
        if (resultsProgressBar != null) {
            resultsProgressBar.setVisibility(View.VISIBLE);
        }

        ApiClient.getApiService(this).getPollResults(pollId).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (resultsProgressBar != null) {
                    resultsProgressBar.setVisibility(View.GONE);
                }
                if (resultsSwipeRefresh != null) {
                    resultsSwipeRefresh.setRefreshing(false);
                }
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        resultsList.clear();
                        JsonObject obj = response.body();

                        if (obj.has("turnout") && obj.get("turnout").isJsonObject()) {
                            JsonObject turnout = obj.getAsJsonObject("turnout");
                            turnoutVotesCast = turnout.has("total_votes_cast") ? turnout.get("total_votes_cast").getAsInt() : 0;
                            turnoutApprovedVoters = turnout.has("approved_voters") ? turnout.get("approved_voters").getAsInt() : 0;
                            turnoutPercentage = turnout.has("turnout_percentage") ? turnout.get("turnout_percentage").getAsDouble() : 0.0;
                        }

                        JsonArray array = null;
                        if (obj.has("candidates") && obj.get("candidates").isJsonArray()) {
                            array = obj.getAsJsonArray("candidates");
                        } else if (obj.has("results") && obj.get("results").isJsonArray()) {
                            array = obj.getAsJsonArray("results");
                        } else if (obj.has("data") && obj.get("data").isJsonArray()) {
                            array = obj.getAsJsonArray("data");
                        }

                        if (array != null) {
                            for (int i = 0; i < array.size(); i++) {
                                if (array.get(i).isJsonObject()) {
                                    resultsList.add(new JSONObject(array.get(i).getAsJsonObject().toString()));
                                }
                            }
                        }

                        renderResults();
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing live results response", e);
                    }
                } else {
                    fetchCandidatesFallback();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                fetchCandidatesFallback();
            }
        });
    }

    private void fetchCandidatesFallback() {
        ApiClient.getApiService(this).getCandidates(pollId).enqueue(new Callback<JsonArray>() {
            @Override
            public void onResponse(@NonNull Call<JsonArray> call, @NonNull Response<JsonArray> response) {
                if (resultsProgressBar != null) {
                    resultsProgressBar.setVisibility(View.GONE);
                }
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        resultsList.clear();
                        JsonArray array = response.body();
                        for (int i = 0; i < array.size(); i++) {
                            if (array.get(i).isJsonObject()) {
                                resultsList.add(new JSONObject(array.get(i).getAsJsonObject().toString()));
                            }
                        }
                        renderResults();
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing fallback candidates response", e);
                    }
                } else {
                    Toast.makeText(LiveResultsActivity.this, "Error fetching results from server", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonArray> call, @NonNull Throwable t) {
                if (resultsProgressBar != null) {
                    resultsProgressBar.setVisibility(View.GONE);
                }
                if (resultsSwipeRefresh != null) {
                    resultsSwipeRefresh.setRefreshing(false);
                }
                Toast.makeText(LiveResultsActivity.this, "Error fetching results from server", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void renderResults() {
        LinearLayout resultsContainer = findViewById(R.id.resultsListContainer);
        TextView turnoutText = findViewById(R.id.turnoutText);
        ProgressBar turnoutProgressBar = findViewById(R.id.turnoutProgressBar);
        BarChart barChart = findViewById(R.id.resultsBarChart);
        TextView resultsEmptyStateText = findViewById(R.id.resultsEmptyStateText);

        if (resultsList.isEmpty()) {
            if (resultsEmptyStateText != null) resultsEmptyStateText.setVisibility(View.VISIBLE);
            if (resultsContainer != null) resultsContainer.removeAllViews();
            return;
        } else {
            if (resultsEmptyStateText != null) resultsEmptyStateText.setVisibility(View.GONE);
        }

        int votesCast = 0;
        int approvedVoters = 0;
        double turnoutPct = 0.0;

        if (turnoutVotesCast != -1 && turnoutApprovedVoters != -1) {
            votesCast = turnoutVotesCast;
            approvedVoters = turnoutApprovedVoters;
            turnoutPct = (turnoutPercentage >= 0) ? turnoutPercentage : ((approvedVoters > 0) ? (votesCast * 100.0 / approvedVoters) : 0.0);
        } else {
            for (JSONObject item : resultsList) {
                int v = item.optInt("votes", item.optInt("vote_count", 0));
                votesCast += v;
                if (item.has("approved_voters")) {
                    approvedVoters = item.optInt("approved_voters");
                } else if (item.has("total_approved_voters")) {
                    approvedVoters = item.optInt("total_approved_voters");
                }
            }
            turnoutPct = (approvedVoters > 0) ? (votesCast * 100.0 / approvedVoters) : 0.0;
        }

        if (turnoutText != null) {
            turnoutText.setText("Voter Turnout: " + votesCast + " of " + approvedVoters + " approved voters (" + String.format(java.util.Locale.US, "%.1f", turnoutPct) + "%)");
        }
        if (turnoutProgressBar != null) {
            turnoutProgressBar.setProgress((int) Math.round(turnoutPct));
        }

        // Render BarChart
        if (barChart != null) {
            ArrayList<BarEntry> entries = new ArrayList<>();
            ArrayList<String> candidateNames = new ArrayList<>();

            for (int i = 0; i < resultsList.size(); i++) {
                JSONObject c = resultsList.get(i);
                int votes = c.optInt("votes", c.optInt("vote_count", 0));
                String name = c.optString("candidate_name", c.optString("name", "Cand " + (i + 1)));

                if (name.length() > 10) {
                    name = name.substring(0, 9) + "…";
                }

                entries.add(new BarEntry(i, votes));
                candidateNames.add(name);
            }

            BarDataSet dataSet = new BarDataSet(entries, "Vote Count");
            dataSet.setColor(Color.parseColor("#1E3A8A"));
            dataSet.setValueTextColor(Color.parseColor("#0F172A"));
            dataSet.setValueTextSize(12f);
            dataSet.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    return String.valueOf((int) value);
                }
            });

            BarData barData = new BarData(dataSet);
            barData.setBarWidth(0.5f);

            barChart.setData(barData);
            barChart.setFitBars(true);
            barChart.setExtraBottomOffset(32f);
            barChart.setExtraLeftOffset(12f);

            XAxis xAxis = barChart.getXAxis();
            xAxis.setValueFormatter(new IndexAxisValueFormatter(candidateNames));
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setGranularity(1f);
            xAxis.setGranularityEnabled(true);
            xAxis.setLabelRotationAngle(-30f);
            xAxis.setDrawGridLines(false);
            xAxis.setTextColor(Color.parseColor("#0F172A"));
            xAxis.setTextSize(10f);

            com.github.mikephil.charting.components.YAxis leftAxis = barChart.getAxisLeft();
            leftAxis.setDrawGridLines(false);
            leftAxis.setTextColor(Color.parseColor("#0F172A"));
            leftAxis.setAxisMinimum(0f);
            leftAxis.setGranularity(1f);
            leftAxis.setGranularityEnabled(true);
            leftAxis.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    return String.valueOf((int) value);
                }
            });

            barChart.getAxisRight().setEnabled(false);
            barChart.getLegend().setEnabled(false);
            barChart.getDescription().setEnabled(false);
            barChart.animateY(800);
            barChart.invalidate();
        }

        // Render List Items inside resultsListContainer for smooth ScrollView scrolling
        if (resultsContainer != null) {
            resultsContainer.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(this);

            for (int position = 0; position < resultsList.size(); position++) {
                View itemView = inflater.inflate(R.layout.item_result, resultsContainer, false);

                TextView nameText = itemView.findViewById(R.id.resultCandidateName);
                TextView partyText = itemView.findViewById(R.id.candidatePartyText);
                TextView voteText = itemView.findViewById(R.id.resultVoteCount);
                TextView voteLabelText = itemView.findViewById(R.id.resultVoteLabel);
                ImageView avatarImage = itemView.findViewById(R.id.candidateAvatar);
                ProgressBar candidateProgressBar = itemView.findViewById(R.id.candidateProgressBar);

                try {
                    JSONObject candidate = resultsList.get(position);
                    String name = candidate.optString("candidate_name", candidate.optString("name", "Unknown"));
                    String party = candidate.optString("party", candidate.optString("party_name", "Independent"));
                    String photoUrl = candidate.optString("photo_url", candidate.optString("image_url", ""));

                    int votes = candidate.optInt("votes", candidate.optInt("vote_count", 0));
                    voteText.setText(String.valueOf(votes));
                    if (voteLabelText != null) {
                        voteLabelText.setText(votes == 1 ? " vote" : " votes");
                    }

                    if (candidateProgressBar != null) {
                        int percent = (votesCast > 0) ? (votes * 100 / votesCast) : 0;
                        candidateProgressBar.setProgress(percent);
                    }

                    if ("CLOSED".equalsIgnoreCase(electionStatus) && position == 0 && votes > 0) {
                        nameText.setText(name + " (WINNER)");
                    } else {
                        nameText.setText(name);
                    }

                    if (partyText != null) {
                        partyText.setText(party != null && !party.isEmpty() ? party : "Independent");
                    }

                    if (avatarImage != null) {
                        Glide.with(LiveResultsActivity.this)
                                .load(photoUrl)
                                .circleCrop()
                                .placeholder(android.R.drawable.ic_menu_myplaces)
                                .error(android.R.drawable.ic_menu_myplaces)
                                .into(avatarImage);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error rendering result item", e);
                }

                resultsContainer.addView(itemView);
            }
        }
    }
}