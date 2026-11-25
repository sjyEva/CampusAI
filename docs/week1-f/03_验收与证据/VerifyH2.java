import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.h2.tools.RunScript;

class VerifyH2 {
    private static void check(String label, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected=" + expected + ", actual=" + actual);
        }
        System.out.println("PASS " + label + " | " + actual);
    }
    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("Missing result");
            return rows.getString(1);
        }
    }
    private static void init(Connection connection, Path folder) throws Exception {
        for (String file : new String[]{"schema.sql", "data.sql"}) {
            try (Reader reader = Files.newBufferedReader(folder.resolve(file), StandardCharsets.UTF_8)) {
                RunScript.execute(connection, reader);
            }
        }
    }
    private static void reject(Statement statement, String label, String sql, String expected) throws SQLException {
        try {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            check(label, expected, e.getSQLState());
            return;
        }
        throw new AssertionError(label + " unexpectedly succeeded");
    }
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]);
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:week1;MODE=MySQL","sa","");
             Statement s = c.createStatement()) {
            System.out.println(c.getMetaData().getDatabaseProductName()+" "+c.getMetaData().getDatabaseProductVersion());
            for (int i = 1; i <= 2; i++) {
                init(c, source);
                check("DB0"+i+" tables", "4", scalar(s, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'"));
                for (String table : new String[]{"campus_course","campus_course_enrollment","rag_material","rag_chunk"}) {
                    check("DB0"+i+" "+table, table.startsWith("campus_") ? "2" : "0", scalar(s,"SELECT COUNT(*) FROM "+table));
                }
            }
            check("DB03 enrollment","CS101=student,SE101=student2",scalar(s,
                "SELECT LISTAGG(c.course_code || '=' || e.student_username, ',') WITHIN GROUP (ORDER BY c.id) "+
                "FROM campus_course c JOIN campus_course_enrollment e ON c.id=e.course_id"));
            reject(s,"DB04 duplicate enrollment","INSERT INTO campus_course_enrollment VALUES(1,'student')","23505");
            reject(s,"DB05 missing course","INSERT INTO campus_course_enrollment VALUES(999,'student')","23506");
            reject(s,"DB06 referenced course","DELETE FROM campus_course WHERE id=1","23503");
            c.setAutoCommit(false);
            s.executeUpdate("INSERT INTO rag_material(id,course_id,filename,content,sha256,parse_status,version,parser_version) "+
                "VALUES('sample',1,'sample.txt',X'31',REPEAT('a',64),'READY',1,'demo')");
            s.executeUpdate("INSERT INTO rag_chunk(id,material_id,version,ordinal,text) VALUES('piece','sample',1,1,'text')");
            s.executeUpdate("DELETE FROM rag_material WHERE id='sample'");
            check("DB07 cascade","0",scalar(s,"SELECT COUNT(*) FROM rag_chunk WHERE material_id='sample'"));
            c.rollback();
        }
    }
}
