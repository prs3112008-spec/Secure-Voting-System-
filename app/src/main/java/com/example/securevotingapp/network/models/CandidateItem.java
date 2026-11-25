package com.example.securevotingapp.network.models;

import java.util.Objects;

public class CandidateItem {
    private final int candidateId;
    private final String name;
    private final String party;
    private final String photoUrl;

    public CandidateItem(int candidateId, String name, String party, String photoUrl) {
        this.candidateId = candidateId;
        this.name = name;
        this.party = party;
        this.photoUrl = photoUrl;
    }

    public int getCandidateId() { return candidateId; }
    public String getName() { return name; }
    public String getParty() { return party; }
    public String getPhotoUrl() { return photoUrl; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CandidateItem item = (CandidateItem) o;
        return candidateId == item.candidateId &&
                Objects.equals(name, item.name) &&
                Objects.equals(party, item.party) &&
                Objects.equals(photoUrl, item.photoUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(candidateId, name, party, photoUrl);
    }
}