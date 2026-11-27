package edu.campusai.rag;

import edu.campusai.rag.RagTypes.Source;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RagPreviewRepository {
    private final JdbcClient jdbc;

    public RagPreviewRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<PreviewChunk> page(long courseId, String materialId, int version, int offset, int rowLimit) {
        return jdbc.sql("""
                SELECT c.ordinal, c.id AS chunk_id, m.id AS material_id, m.course_id, c.version,
                       m.filename, c.text, c.page_number, c.paragraph_start, c.paragraph_end, c.heading,
                       l.slide_number, l.sheet_name, l.cell_range
                FROM rag_chunk c JOIN rag_material m ON c.material_id = m.id
                LEFT JOIN rag_chunk_location l ON l.chunk_id = c.id
                WHERE m.course_id = :course AND m.id = :material AND c.version = :version
                  AND m.published = TRUE AND m.parse_status = 'READY' AND c.version = m.version
                  AND c.ordinal > :offset
                ORDER BY c.ordinal
                LIMIT :rowLimit
                """).param("course", courseId).param("material", materialId).param("version", version)
                .param("offset", offset).param("rowLimit", rowLimit)
                .query((row, index) -> new PreviewChunk(row.getInt("ordinal"), new Source(
                        row.getString("chunk_id"), row.getString("material_id"), row.getLong("course_id"),
                        row.getInt("version"), row.getString("filename"), row.getString("text"),
                        row.getObject("page_number", Integer.class), row.getObject("paragraph_start", Integer.class),
                        row.getObject("paragraph_end", Integer.class), row.getString("heading"),
                        row.getObject("slide_number", Integer.class), row.getString("sheet_name"),
                        row.getString("cell_range")))).list();
    }

    public record PreviewChunk(int ordinal, Source source) {}
}
