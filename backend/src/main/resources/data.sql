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

INSERT INTO campus_course_chapter (id, course_id, title, sort_order)
SELECT 101, 1, '计算机系统基础', 2
WHERE NOT EXISTS (SELECT 1 FROM campus_course_chapter WHERE id = 101);

INSERT INTO campus_course_chapter (id, course_id, title, sort_order)
SELECT 102, 1, '课程导论', 1
WHERE NOT EXISTS (SELECT 1 FROM campus_course_chapter WHERE id = 102);

INSERT INTO campus_course_chapter (id, course_id, title, sort_order)
SELECT 201, 2, '软件需求与分析', 1
WHERE NOT EXISTS (SELECT 1 FROM campus_course_chapter WHERE id = 201);
