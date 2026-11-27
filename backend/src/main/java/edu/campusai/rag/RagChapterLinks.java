package edu.campusai.rag;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Optional business metadata; file versions and source identities remain owned by the material. */
@Repository
public class RagChapterLinks {
    private final JdbcClient jdbc;
    public RagChapterLinks(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void requireChapter(long courseId, Long chapterId) {
        requireChapter(courseId, chapterId, false);
    }

    public void requireChapterForWrite(long courseId, Long chapterId) {
        requireChapter(courseId, chapterId, true);
    }

    private void requireChapter(long courseId, Long chapterId, boolean lock) {
        if (chapterId == null) return;
        if (chapterId < 1) throw new RagException(400, "chapterId 必须是正整数");
        Long owner = jdbc.sql("SELECT course_id FROM campus_course_chapter WHERE id=:chapter" + (lock ? " FOR UPDATE" : ""))
                .param("chapter", chapterId).query(Long.class).optional().orElse(null);
        if (owner == null || owner != courseId) {
            throw new RagException(404, "章节不存在或不属于当前课程");
        }
    }

    // Caller holds the material row lock, so concurrent assignments cannot duplicate a link.
    public void assign(String materialId, Long chapterId) {
        jdbc.sql("DELETE FROM rag_material_chapter WHERE material_id=:id").param("id", materialId).update();
        if (chapterId != null) {
            jdbc.sql("INSERT INTO rag_material_chapter(material_id,chapter_id) VALUES(:id,:chapter)")
                    .param("id", materialId).param("chapter", chapterId).update();
        }
    }
}
