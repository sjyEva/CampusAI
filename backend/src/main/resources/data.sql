INSERT INTO campus_course (id, course_code, name, description, teacher_username)
SELECT 1, 'CS101', '计算机导论', '了解计算机系统与程序设计基础。', 'teacher'
WHERE NOT EXISTS (SELECT 1 FROM campus_course WHERE id = 1);

INSERT INTO campus_course (id, course_code, name, description, teacher_username)
SELECT 2, 'SE101', '软件工程', '学习需求分析、系统设计与团队协作。', 'teacher'
WHERE NOT EXISTS (SELECT 1 FROM campus_course WHERE id = 2);

INSERT INTO campus_course_enrollment (course_id, student_username)
SELECT 1, 'student'
WHERE NOT EXISTS (SELECT 1 FROM campus_course_enrollment WHERE course_id = 1 AND student_username = 'student');

INSERT INTO campus_course_enrollment (course_id, student_username)
SELECT 2, 'student2'
WHERE NOT EXISTS (SELECT 1 FROM campus_course_enrollment WHERE course_id = 2 AND student_username = 'student2');
