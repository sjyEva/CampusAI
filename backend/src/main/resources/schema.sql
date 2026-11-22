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
