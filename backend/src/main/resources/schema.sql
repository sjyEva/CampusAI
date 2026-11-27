CREATE TABLE IF NOT EXISTS campus_course (
    id BIGINT PRIMARY KEY,
    course_code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(400) NOT NULL,
    teacher_username VARCHAR(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS campus_course_enrollment (
    course_id BIGINT NOT NULL,
    student_username VARCHAR(50) NOT NULL,
    PRIMARY KEY (course_id, student_username),
    FOREIGN KEY (course_id) REFERENCES campus_course(id)
);

CREATE TABLE IF NOT EXISTS campus_course_chapter (
    id BIGINT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    sort_order INT NOT NULL,
    FOREIGN KEY (course_id) REFERENCES campus_course(id)
);

-- E's module-owned ingestion adapter. B may call RagMaterialService from its
-- material lifecycle; do not maintain a second independent published flag.
CREATE TABLE IF NOT EXISTS rag_material (
    id VARCHAR(36) PRIMARY KEY,
    course_id BIGINT NOT NULL,
    filename VARCHAR(180) NOT NULL,
    content LONGBLOB NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    parse_status VARCHAR(20) NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL,
    parser_version VARCHAR(30) NOT NULL,
    error_message VARCHAR(300),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (course_id) REFERENCES campus_course(id)
);

CREATE TABLE IF NOT EXISTS rag_chunk (
    id VARCHAR(36) PRIMARY KEY,
    material_id VARCHAR(36) NOT NULL,
    version INT NOT NULL,
    ordinal INT NOT NULL,
    text TEXT NOT NULL,
    page_number INT,
    paragraph_start INT,
    paragraph_end INT,
    heading VARCHAR(700),
    UNIQUE (material_id, version, ordinal),
    FOREIGN KEY (material_id) REFERENCES rag_material(id) ON DELETE CASCADE
);

-- Additive extension: existing PDF/DOCX/TXT chunks need no migration or rewrite.
-- Both H2 and MySQL cascade location deletion with the owning chunk.
CREATE TABLE IF NOT EXISTS rag_chunk_location (
    chunk_id VARCHAR(36) PRIMARY KEY,
    slide_number INT,
    sheet_name VARCHAR(255),
    cell_range VARCHAR(64),
    FOREIGN KEY (chunk_id) REFERENCES rag_chunk(id) ON DELETE CASCADE
);

-- Additive week-two metadata: old materials keep their UUID, version and sources.
-- A chapter may only be assigned through the service after checking course ownership.
-- Deleting a chapter with attached materials is rejected until they are reassigned.
CREATE TABLE IF NOT EXISTS rag_material_chapter (
    material_id VARCHAR(36) PRIMARY KEY,
    chapter_id BIGINT NOT NULL,
    FOREIGN KEY (material_id) REFERENCES rag_material(id) ON DELETE CASCADE,
    FOREIGN KEY (chapter_id) REFERENCES campus_course_chapter(id)
);
