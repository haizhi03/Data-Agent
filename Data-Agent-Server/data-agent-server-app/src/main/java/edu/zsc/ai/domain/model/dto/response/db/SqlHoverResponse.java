package edu.zsc.ai.domain.model.dto.response.db;

/**
 * Hover content for one editor token.
 *
 * @param markdown   rendered hover body; null or blank means nothing to show
 * @param rangeStart UTF-16 offset of the hovered token in the request SQL
 * @param rangeEnd   UTF-16 offset where the hovered token ends
 */
public record SqlHoverResponse(String markdown, int rangeStart, int rangeEnd) {
    public static SqlHoverResponse empty() {
        return new SqlHoverResponse(null, 0, 0);
    }
}
