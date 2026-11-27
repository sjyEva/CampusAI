package edu.campusai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class RagChapterMigrationTest {
    @Test void additiveMigrationPreservesWeekOneMaterialVersionsAndSourcesOnRepeat() throws Exception {
        // Fixture is the schema from main 39851812ff6d22c85ea6f105da10b19577cebbb4.
        try(Connection db=DriverManager.getConnection("jdbc:h2:mem:week2_migration;MODE=MySQL","sa","")) {
            ScriptUtils.executeSqlScript(db,new ClassPathResource("rag-week1-schema.sql"));
            db.createStatement().execute("INSERT INTO campus_course VALUES(1,'OLD','Old course','Existing data','teacher')");
            db.createStatement().execute("INSERT INTO campus_course_enrollment VALUES(1,'student')");
            db.createStatement().execute("INSERT INTO campus_course_chapter VALUES(102,1,'Existing chapter',1)");
            db.createStatement().execute("INSERT INTO rag_material(id,course_id,filename,content,sha256,parse_status,published,version,parser_version) VALUES('old-material',1,'old.txt',X'4142','digest','READY',TRUE,7,'office-v2-smartcn-v1')");
            db.createStatement().execute("INSERT INTO rag_chunk(id,material_id,version,ordinal,text,paragraph_start,paragraph_end) VALUES('old-source','old-material',7,1,'Existing text',3,4)");
            for(int i=0;i<2;i++) {
                ScriptUtils.executeSqlScript(db,new ClassPathResource("db/migration/V20261127__rag_material_chapter.sql"));
                ScriptUtils.executeSqlScript(db,new ClassPathResource("schema.sql"));
            }
            var row=db.createStatement().executeQuery("SELECT m.version,m.published,m.content,c.id,c.text,c.paragraph_start,c.paragraph_end FROM rag_material m JOIN rag_chunk c ON c.material_id=m.id WHERE m.id='old-material'");
            assertTrue(row.next());assertEquals(7,row.getInt(1));assertTrue(row.getBoolean(2));
            assertArrayEquals(new byte[]{65,66},row.getBytes(3));assertEquals("old-source",row.getString(4));
            assertEquals("Existing text",row.getString(5));assertEquals(3,row.getInt(6));assertEquals(4,row.getInt(7));
            assertFalse(row.next());
            row=db.createStatement().executeQuery("SELECT COUNT(*) FROM rag_material_chapter");row.next();assertEquals(0,row.getInt(1));
            row=db.createStatement().executeQuery("SELECT COUNT(*) FROM campus_course_enrollment WHERE course_id=1 AND student_username='student'");row.next();assertEquals(1,row.getInt(1));
            db.createStatement().execute("INSERT INTO rag_material_chapter VALUES('old-material',102)");
            db.createStatement().execute("DELETE FROM rag_material WHERE id='old-material'");
            row=db.createStatement().executeQuery("SELECT COUNT(*) FROM rag_material_chapter");row.next();assertEquals(0,row.getInt(1));
        }
    }
}
