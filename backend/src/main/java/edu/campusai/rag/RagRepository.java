package edu.campusai.rag;

import edu.campusai.rag.RagTypes.Material;
import edu.campusai.rag.RagTypes.Source;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class RagRepository {
    private static final String LIVE = "m.published = TRUE AND m.parse_status = 'READY' AND c.version = m.version";
    private static final String SOURCE_SELECT = """
            SELECT c.id AS chunk_id, m.id AS material_id, m.course_id, c.version,
                   m.filename, c.text, c.page_number, c.paragraph_start, c.paragraph_end, c.heading
            FROM rag_chunk c JOIN rag_material m ON c.material_id = m.id
            """;
    private static final String MATERIAL_SELECT = """
            SELECT m.id, m.course_id, m.filename, m.parse_status, m.published, m.version,
                   (SELECT COUNT(*) FROM rag_chunk c WHERE c.material_id=m.id AND c.version=m.version) AS chunk_count,
                   m.error_message FROM rag_material m
            """;
    private final JdbcClient jdbc;

    public RagRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Material> list(long courseId, boolean management) {
        return jdbc.sql(MATERIAL_SELECT + " WHERE m.course_id = :course" +
                        (management ? "" : " AND m.published=TRUE AND m.parse_status='READY'") + " ORDER BY m.created_at,m.id")
                .param("course", courseId).query(Material.class).list();
    }

    public Material material(long courseId, String id) {
        return jdbc.sql(MATERIAL_SELECT + " WHERE m.id=:id AND m.course_id=:course")
                .param("id", id).param("course", courseId).query(Material.class).optional()
                .orElseThrow(() -> new RagException(404, "资料不存在或无访问权限"));
    }

    public StoredMaterial stored(long courseId, String id, boolean lock) {
        return jdbc.sql("SELECT id,course_id,filename,content,sha256,parse_status,published,version,parser_version FROM rag_material WHERE id=:id AND course_id=:course" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).param("course", courseId).query(StoredMaterial.class).optional()
                .orElseThrow(() -> new RagException(404, "资料不存在或无访问权限"));
    }

    public void create(String id, long courseId, String filename, byte[] content, String sha) {
        jdbc.sql("""
                INSERT INTO rag_material(id,course_id,filename,content,sha256,parse_status,published,version,parser_version)
                VALUES(:id,:course,:filename,:content,:sha,'PROCESSING',FALSE,0,'')
                """).param("id", id).param("course", courseId).param("filename", filename)
                .param("content", content).param("sha", sha).update();
    }

    public void begin(String id, String filename, byte[] content, String sha, int version, String parserVersion) {
        jdbc.sql("""
                UPDATE rag_material SET filename=:filename,content=:content,sha256=:sha,
                parse_status='PROCESSING',published=FALSE,version=:version,parser_version=:parser,
                error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id
                """).param("id", id).param("filename", filename).param("content", content)
                .param("sha", sha).param("version", version).param("parser", parserVersion).update();
        jdbc.sql("DELETE FROM rag_chunk WHERE material_id=:id").param("id", id).update();
    }

    public void insertChunk(String id, String materialId, int version, int ordinal, DocumentParser.ParsedChunk chunk) {
        jdbc.sql("""
                INSERT INTO rag_chunk(id,material_id,version,ordinal,text,page_number,paragraph_start,paragraph_end,heading)
                VALUES(:id,:material,:version,:ordinal,:text,:page,:start,:end,:heading)
                """).param("id", id).param("material", materialId).param("version", version)
                .param("ordinal", ordinal).param("text", chunk.text()).param("page", chunk.pageNumber())
                .param("start", chunk.paragraphStart()).param("end", chunk.paragraphEnd())
                .param("heading", chunk.heading()).update();
    }

    public void finish(String id, String status, String error) {
        jdbc.sql("UPDATE rag_material SET parse_status=:status,error_message=:error,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("status", status).param("error", error).update();
    }

    public void publish(String id, boolean published) {
        jdbc.sql("UPDATE rag_material SET published=:published,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("published", published).update();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM rag_material WHERE id=:id").param("id", id).update();
    }

    public List<Source> activeSources(long courseId) {
        return jdbc.sql(SOURCE_SELECT + " WHERE m.course_id=:course AND " + LIVE + " ORDER BY c.id")
                .param("course", courseId).query(Source.class).list();
    }

    public Optional<Source> source(long courseId, String id) {
        return jdbc.sql(SOURCE_SELECT + " WHERE m.course_id=:course AND c.id=:id AND " + LIVE)
                .param("course", courseId).param("id", id).query(Source.class).optional();
    }

    public boolean allCurrent(long courseId, List<Source> sources) {
        if (sources.isEmpty()) return true;
        List<String> ids = sources.stream().map(Source::chunkId).distinct().toList();
        long count = jdbc.sql("SELECT COUNT(*) FROM rag_chunk c JOIN rag_material m ON m.id=c.material_id WHERE m.course_id=:course AND c.id IN (:ids) AND " + LIVE)
                .param("course", courseId).param("ids", ids).query(Long.class).single();
        return count == ids.size();
    }

    public record StoredMaterial(String id, long courseId, String filename, byte[] content, String sha256,
                                 String parseStatus, boolean published, int version, String parserVersion) {}
}
