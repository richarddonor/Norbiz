package com.chardizard.Norbiz.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * item_image_embeddings: one pgvector CLIP embedding per item reference image (docs/ITEM_RECOGNITION.md).
 * Plain JDBC rather than a JPA entity — it's a derived search index, not domain data, so it isn't
 * Auditable, isn't cached, and Hibernate never needs to know the vector type.
 */
@Repository
@RequiredArgsConstructor
public class ItemImageEmbeddingRepository {

    private final JdbcTemplate jdbc;

    public record Match(long itemId, String imagePath, double similarity) {
    }

    /** Items currently have one reference image, so indexing replaces whatever the item had. */
    public void replaceForItem(long companyId, long itemId, String imagePath, String model, float[] embedding) {
        jdbc.update("DELETE FROM item_image_embeddings WHERE item_id = ?", itemId);
        jdbc.update("""
                INSERT INTO item_image_embeddings (company_id, item_id, image_path, model, embedding)
                VALUES (?, ?, ?, ?, CAST(? AS vector))
                """, companyId, itemId, imagePath, model, toLiteral(embedding));
    }

    /** Item ids of the company that already have an embedding for their current image (any model). */
    public Set<Long> indexedItemIds(long companyId) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT e.item_id FROM item_image_embeddings e
                JOIN items i ON i.id = e.item_id AND i.image_path = e.image_path
                WHERE e.company_id = ?
                """, Long.class, companyId));
    }

    /**
     * The company's items closest to the query embedding, best first, one row per item (its closest
     * image). Only embeddings made by the same model are comparable. An exact scan over the
     * company's rows, filtered by the (company_id, model) index — fine into the tens of thousands of
     * items per company; beyond that add an HNSW index (see docs/ITEM_RECOGNITION.md).
     */
    public List<Match> nearest(long companyId, String model, float[] query, boolean activeOnly, String tag,
                               double minSimilarity, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT item_id, image_path, similarity FROM (
                    SELECT DISTINCT ON (e.item_id) e.item_id, e.image_path,
                           1 - (e.embedding <=> CAST(? AS vector)) AS similarity
                    FROM item_image_embeddings e
                    JOIN items i ON i.id = e.item_id
                    WHERE e.company_id = ? AND e.model = ?
                """);
        List<Object> args = new ArrayList<>(List.of(toLiteral(query), companyId, model));
        if (activeOnly) {
            sql.append(" AND i.active\n");
        }
        if (tag != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM item_tags t WHERE t.item_id = i.id AND t.tag = ?)\n");
            args.add(tag);
        }
        sql.append("""
                    ORDER BY e.item_id, similarity DESC
                ) best
                WHERE similarity >= ?
                ORDER BY similarity DESC
                LIMIT ?
                """);
        args.add(minSimilarity);
        args.add(limit);
        return jdbc.query(sql.toString(),
                (rs, i) -> new Match(rs.getLong("item_id"), rs.getString("image_path"), rs.getDouble("similarity")),
                args.toArray());
    }

    // pgvector's text input format: [0.1,0.2,...]
    private static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
