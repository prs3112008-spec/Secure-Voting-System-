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

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.bumptech.glide.Glide;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LiveResultsActivity extends AppCompatActivity {

    private static final String TAG = "LiveResultsActivity";

    ListView resultsListView;
    ProgressBar resultsProgressBar;
    List<JSONObject> resultsList = new ArrayList<>();
    ResultsAdapter adapter;
    private RequestQueue requestQueue;
    private String electionStatus = "LIVE";

    private int turnoutVotesCast = -1;
    private int turnoutApprovedVoters = -1;
    private double turnoutPercentage = -1.0;
    private int pollId = 1;
    private String pollTitle = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        super.setContentView(R.layout.activity_live_results);

        pollId = getIntent().getIntExtra("EXTRA_POLL_ID", 1);
        pollTitle = getIntent().getStringExtra("EXTRA_POLL_TITLE");

        if (pollTitle != null && !pollTitle.isEmpty()) {
            TextView titleView = findViewById(R.id.liveResultsTitle);
            if (titleView != null) {
                titleView.setText("Poll #" + pollId + ": " + pollTitle);
            }
        }

        requestQueue = Volley.newRequestQueue(this);
        resultsListView = findViewById(R.id.resultsListView);
        resultsProgressBar = findViewById(R.id.resultsProgressBar);
        adapter = new ResultsAdapter();
        if (resultsListView != null) {
            resultsListView.setAdapter(adapter);
        }

        fetchElectionStatus();
        fetchLiveResults();
    }

    private void fetchElectionStatus() {
        String url = "https://secure-voting-system-9rnd.onrender.com/api/votes/election-status/" + pollId;

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.GET,
                url,
                null,
                response -> {
                    electionStatus = response.optString("status", "LIVE");
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    renderResults();
                },
                error -> Log.e(TAG, "Error fetching election status")
        );

        request.setRetryPolicy(new com.android.volley.DefaultRetryPolicy(
                30000,
                com.android.volley.DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                com.android.volley.DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        requestQueue.add(request);
    }

    private void fetchLiveResults() {
        if (resultsProgressBar != null) {
            resultsProgressBar.setVisibility(View.VISIBLE);
        }

        String url = "https://secure-voting-system-9rnd.onrender.com/api/polls/" + pollId + "/results";

        SharedPreferences sharedPreferences = SecurePrefs.get(this);
        String token = sharedPreferences.getString("jwt_token", null);

        com.android.volley.toolbox.StringRequest stringRequest = new com.android.volley.toolbox.StringRequest(
                Request.Method.GET,
                url,
                response -> {
                    if (resultsProgressBar != null) {
                        resultsProgressBar.setVisibility(View.GONE);
                    }
                    try {
                        resultsList.clear();
                        String trimmed = response != null ? response.trim() : "";
                        JSONArray array = null;

                        if (trimmed.startsWith("[")) {
                            array = new JSONArray(trimmed);
                        } else if (trimmed.startsWith("{")) {
                            JSONObject obj = new JSONObject(trimmed);
                            if (obj.has("turnout")) {
                                JSONObject turnout = obj.optJSONObject("turnout");
                                if (turnout != null) {
                                    turnoutVotesCast = turnout.optInt("total_votes_cast", 0);
                                    turnoutApprovedVoters = turnout.optInt("approved_voters", 0);
                                    turnoutPercentage = turnout.optDouble("turnout_percentage", 0.0);
                                    Log.d(TAG, "Parsed turnout: " + turnoutVotesCast + " of " + turnoutApprovedVoters + " (" + turnoutPercentage + "%)");
                                }
                            }
                            array = obj.optJSONArray("candidates");
                            if (array == null) array = obj.optJSONArray("results");
                            if (array == null) array = obj.optJSONArray("data");
                        }

                        if (array != null) {
                            for (int i = 0; i < array.length(); i++) {
                                resultsList.add(array.getJSONObject(i));
                            }
                        }

                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                        renderResults();
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing live results response", e);
                    }
                },
                error -> {
                    // Fallback to candidates endpoint if polls results endpoint fails
                    fetchCandidatesFallback(token);
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                if (token != null) {
                    headers.put("Authorization", "Bearer " + token);
                }
                return headers;
            }
        };

        stringRequest.setRetryPolicy(new com.android.volley.DefaultRetryPolicy(
                30000,
                com.android.volley.DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                com.android.volley.DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        requestQueue.add(stringRequest);
    }

    private void fetchCandidatesFallback(String token) {
        String fallbackUrl = "https://secure-voting-system-9rnd.onrender.com/api/candidates/" + pollId;

        com.android.volley.toolbox.StringRequest stringRequest = new com.android.volley.toolbox.StringRequest(
                Request.Method.GET,
                fallbackUrl,
                response -> {
                    if (resultsProgressBar != null) {
                        resultsProgressBar.setVisibility(View.GONE);
                    }
                    try {
                        resultsList.clear();
                        String trimmed = response != null ? response.trim() : "";
                        JSONArray array = null;

                        if (trimmed.startsWith("[")) {
                            array = new JSONArray(trimmed);
                        } else if (trimmed.startsWith("{")) {
                            JSONObject obj = new JSONObject(trimmed);
                            if (obj.has("turnout")) {
                                JSONObject turnout = obj.optJSONObject("turnout");
                                if (turnout != null) {
                                    turnoutVotesCast = turnout.optInt("total_votes_cast", 0);
                                    turnoutApprovedVoters = turnout.optInt("approved_voters", 0);
                                    turnoutPercentage = turnout.optDouble("turnout_percentage", 0.0);
                                }
                            }
                            array = obj.optJSONArray("candidates");
                            if (array == null) array = obj.optJSONArray("results");
                            if (array == null) array = obj.optJSONArray("data");
                        }

                        if (array != null) {
                            for (int i = 0; i < array.length(); i++) {
                                resultsList.add(array.getJSONObject(i));
                            }
                        }

                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                        renderResults();
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing fallback results response", e);
                    }
                },
                error -> {
                    if (resultsProgressBar != null) {
                        resultsProgressBar.setVisibility(View.GONE);
                    }
                    Toast.makeText(LiveResultsActivity.this, "Error fetching results from server", Toast.LENGTH_SHORT).show();
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                if (token != null) {
                    headers.put("Authorization", "Bearer " + token);
                }
                return headers;
            }
        };

        stringRequest.setRetryPolicy(new com.android.volley.DefaultRetryPolicy(
                30000,
                com.android.volley.DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                com.android.volley.DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        requestQueue.add(stringRequest);
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

    // Custom Adapter to bind JSON data to your Midnight Blue UI cards
    private class ResultsAdapter extends BaseAdapter {
        @Override
        public int getCount() { return resultsList.size(); }
        @Override
        public Object getItem(int position) { return resultsList.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(LiveResultsActivity.this).inflate(R.layout.item_result, parent, false);
            }

            TextView nameText = convertView.findViewById(R.id.resultCandidateName);
            TextView partyText = convertView.findViewById(R.id.candidatePartyText);
            TextView voteText = convertView.findViewById(R.id.resultVoteCount);
            TextView voteLabelText = convertView.findViewById(R.id.resultVoteLabel);
            ImageView avatarImage = convertView.findViewById(R.id.candidateAvatar);

            try {
                JSONObject candidate = resultsList.get(position);
                String name = candidate.optString("candidate_name", candidate.optString("name", "Unknown"));
                String party = candidate.optString("party", candidate.optString("party_name", "Independent"));
                String photoUrl = candidate.optString("photo_url", candidate.optString("image_url", ""));

                int votes = 0;
                if (candidate.has("votes")) {
                    votes = candidate.getInt("votes");
                } else if (candidate.has("vote_count")) {
                    votes = candidate.getInt("vote_count");
                }
                voteText.setText(String.valueOf(votes));
                if (voteLabelText != null) {
                    voteLabelText.setText(votes == 1 ? " vote" : " votes");
                }

                int totalVotes = 0;
                for (JSONObject item : resultsList) {
                    if (item.has("votes")) {
                        totalVotes += item.optInt("votes");
                    } else if (item.has("vote_count")) {
                        totalVotes += item.optInt("vote_count");
                    }
                }

                ProgressBar candidateProgressBar = convertView.findViewById(R.id.candidateProgressBar);
                if (candidateProgressBar != null) {
                    int percent = (totalVotes > 0) ? (votes * 100 / totalVotes) : 0;
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
            } catch (JSONException e) {
                Log.e(TAG, "Error rendering candidate result item", e);
            }

            return convertView;
        }
    }
}