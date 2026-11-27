-- Manual additive migration for environments with spring.sql.init.mode=never.
-- Requires the existing rag_material and campus_course_chapter tables.
-- Existing materials are unassigned; do not invent chapter mappings for old data.
CREATE TABLE IF NOT EXISTS rag_material_chapter (
    material_id VARCHAR(36) PRIMARY KEY,
    chapter_id BIGINT NOT NULL,
    FOREIGN KEY (material_id) REFERENCES rag_material(id) ON DELETE CASCADE,
    FOREIGN KEY (chapter_id) REFERENCES campus_course_chapter(id)
);
