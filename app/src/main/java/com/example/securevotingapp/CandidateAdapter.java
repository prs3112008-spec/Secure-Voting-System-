package com.example.securevotingapp;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.securevotingapp.network.models.CandidateItem;

public class CandidateAdapter extends ListAdapter<CandidateItem, CandidateAdapter.CandidateViewHolder> {

    public interface OnVoteClickListener {
        void onVoteClick(int position);
    }

    public interface HasVotedProvider {
        boolean hasVoted();
    }

    public interface PollStatusProvider {
        boolean isPollClosed();
    }

    private final HasVotedProvider hasVotedProvider;
    private final PollStatusProvider pollStatusProvider;
    private final OnVoteClickListener voteClickListener;

    private static final DiffUtil.ItemCallback<CandidateItem> DIFF_CALLBACK = new DiffUtil.ItemCallback<CandidateItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull CandidateItem oldItem, @NonNull CandidateItem newItem) {
            return oldItem.getCandidateId() == newItem.getCandidateId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull CandidateItem oldItem, @NonNull CandidateItem newItem) {
            return oldItem.equals(newItem);
        }
    };

    public CandidateAdapter(HasVotedProvider hasVotedProvider,
                            PollStatusProvider pollStatusProvider,
                            OnVoteClickListener voteClickListener) {
        super(DIFF_CALLBACK);
        this.hasVotedProvider = hasVotedProvider;
        this.pollStatusProvider = pollStatusProvider;
        this.voteClickListener = voteClickListener;
    }

    @NonNull
    @Override
    public CandidateViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_candidate, parent, false);
        return new CandidateViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CandidateViewHolder holder, int position) {
        CandidateItem item = getItem(position);
        Context context = holder.itemView.getContext();

        holder.nameTextView.setText(item.getName() != null ? item.getName() : "Unknown");
        holder.partyTextView.setText(item.getParty() != null && !item.getParty().isEmpty() ? item.getParty() : "Independent");

        Glide.with(context)
                .load(item.getPhotoUrl())
                .circleCrop()
                .placeholder(android.R.drawable.ic_menu_myplaces)
                .error(android.R.drawable.ic_menu_myplaces)
                .into(holder.avatarImageView);

        boolean userHasVoted = (hasVotedProvider != null) && hasVotedProvider.hasVoted();
        boolean isClosed = (pollStatusProvider != null) && pollStatusProvider.isPollClosed();

        if (isClosed) {
            holder.voteButton.setEnabled(false);
            holder.voteButton.setText("POLLS CLOSED");
            holder.voteButton.setBackgroundColor(Color.parseColor("#94A3B8"));
            holder.voteButton.setTextColor(Color.WHITE);
        } else if (userHasVoted) {
            holder.voteButton.setEnabled(true);
            holder.voteButton.setText("BALLOT RECORDED");
            holder.voteButton.setBackgroundColor(Color.parseColor("#94A3B8"));
            holder.voteButton.setTextColor(Color.WHITE);
        } else {
            holder.voteButton.setEnabled(true);
            holder.voteButton.setText("TAP TO VOTE");
            holder.voteButton.setBackgroundColor(Color.parseColor("#1E3A8A"));
            holder.voteButton.setTextColor(Color.WHITE);
        }

        holder.voteButton.setOnClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (voteClickListener != null && currentPosition != RecyclerView.NO_POSITION) {
                voteClickListener.onVoteClick(currentPosition);
            }
        });
    }

    public static class CandidateViewHolder extends RecyclerView.ViewHolder {
        final TextView nameTextView;
        final TextView partyTextView;
        final ImageView avatarImageView;
        final TextView voteButton;

        public CandidateViewHolder(@NonNull View itemView) {
            super(itemView);
            nameTextView = itemView.findViewById(R.id.candidateNameText);
            partyTextView = itemView.findViewById(R.id.candidatePartyText);
            avatarImageView = itemView.findViewById(R.id.candidateAvatar);
            voteButton = itemView.findViewById(R.id.voteButton);
        }
    }
}