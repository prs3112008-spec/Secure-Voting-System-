package com.example.securevotingapp;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import java.util.ArrayList;

public class CandidateAdapter extends ArrayAdapter<String> {

    public interface OnVoteClickListener {
        void onVoteClick(int position);
    }

    public interface VotedCandidateProvider {
        int getVotedCandidateId();
    }

    public interface PollStatusProvider {
        boolean isPollClosed();
    }

    private final ArrayList<Integer> candidateIdList;
    private final ArrayList<String> partyList;
    private final ArrayList<String> photoUrlList;
    private final VotedCandidateProvider votedCandidateProvider;
    private final PollStatusProvider pollStatusProvider;
    private final OnVoteClickListener voteClickListener;

    public CandidateAdapter(Context context,
                            ArrayList<String> candidates,
                            ArrayList<Integer> candidateIdList,
                            ArrayList<String> partyList,
                            ArrayList<String> photoUrlList,
                            VotedCandidateProvider votedCandidateProvider,
                            PollStatusProvider pollStatusProvider,
                            OnVoteClickListener voteClickListener) {
        super(context, 0, candidates);
        this.candidateIdList = candidateIdList;
        this.partyList = partyList;
        this.photoUrlList = photoUrlList;
        this.votedCandidateProvider = votedCandidateProvider;
        this.pollStatusProvider = pollStatusProvider;
        this.voteClickListener = voteClickListener;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_candidate, parent, false);
        }

        String candidateName = getItem(position);
        String party = (partyList != null && position < partyList.size()) ? partyList.get(position) : "Independent";
        String photoUrl = (photoUrlList != null && position < photoUrlList.size()) ? photoUrlList.get(position) : null;

        TextView nameTextView = convertView.findViewById(R.id.candidateNameText);
        if (nameTextView != null && candidateName != null) {
            nameTextView.setText(candidateName);
        }

        TextView partyTextView = convertView.findViewById(R.id.candidatePartyText);
        if (partyTextView != null) {
            partyTextView.setText(party != null && !party.isEmpty() ? party : "Independent");
        }

        ImageView avatarImageView = convertView.findViewById(R.id.candidateAvatar);
        if (avatarImageView != null) {
            Glide.with(getContext())
                    .load(photoUrl)
                    .circleCrop()
                    .placeholder(android.R.drawable.ic_menu_myplaces)
                    .error(android.R.drawable.ic_menu_myplaces)
                    .into(avatarImageView);
        }

        TextView voteButton = convertView.findViewById(R.id.voteButton);
        if (voteButton != null) {
            int votedCandidateId = (votedCandidateProvider != null) ? votedCandidateProvider.getVotedCandidateId() : -1;
            int currentCandidateId = (candidateIdList != null && position < candidateIdList.size()) ? candidateIdList.get(position) : -1;
            boolean isClosed = (pollStatusProvider != null) && pollStatusProvider.isPollClosed();

            if (isClosed && currentCandidateId != votedCandidateId) {
                voteButton.setEnabled(false);
                voteButton.setText("POLLS CLOSED");
                voteButton.setBackgroundColor(Color.parseColor("#94A3B8"));
                voteButton.setTextColor(Color.WHITE);
            } else if (votedCandidateId != -1) {
                // Keep button enabled so clicking "YOUR VOTE" can open receipt dialog
                voteButton.setEnabled(true);

                if (currentCandidateId == votedCandidateId) {
                    // This is the person they voted for! Turn it green.
                    voteButton.setText("YOUR VOTE");
                    voteButton.setBackgroundColor(Color.parseColor("#059669")); // Emerald green
                    voteButton.setTextColor(Color.WHITE);
                } else {
                    // Not their chosen candidate. Gray it out.
                    voteButton.setText("TAP TO VOTE");
                    voteButton.setBackgroundColor(Color.parseColor("#94A3B8")); // Neutral slate
                    voteButton.setTextColor(Color.WHITE);
                }
            } else {
                // User hasn't voted yet. Keep buttons clickable and normal dark navy.
                voteButton.setEnabled(true);
                voteButton.setText("TAP TO VOTE");
                voteButton.setBackgroundColor(Color.parseColor("#1E3A8A")); // Primary dark navy
                voteButton.setTextColor(Color.WHITE);
            }

            voteButton.setOnClickListener(v -> {
                if (voteClickListener != null) {
                    voteClickListener.onVoteClick(position);
                }
            });
        }

        return convertView;
    }
}