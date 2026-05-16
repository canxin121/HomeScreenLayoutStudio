package dev.canxin.homescreenlayoutstudio.rules;

public final class MatchHit {
    public final String category;
    public final String field;
    public final String matchType;
    public final String keyword;

    MatchHit(String category, String field, String matchType, String keyword) {
        this.category = category;
        this.field = field;
        this.matchType = matchType;
        this.keyword = keyword;
    }
}
