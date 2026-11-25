"""Verify CampusAI seed SQL on a new, short-lived local MySQL 8 instance (Windows)."""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time

HERE = Path(__file__).resolve().parent
SQL = HERE.parent / "02_初始化与样例"
DB = "campus_f_week1"


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def check_mysql(bin_dir, jar, repo):
    server = str(bin_dir / "mysqld.exe")
    client = str(bin_dir / "mysql.exe")
    admin = str(bin_dir / "mysqladmin.exe")
    version = subprocess.check_output([server, "--no-defaults", "--version"], text=True).strip()
    records = []
    def expect(case, expected, actual):
        records.append(dict(case=case, expected=expected, actual=actual, passed=actual == expected))
        print(json.dumps(records[-1], ensure_ascii=False), flush=True)
        if actual != expected:
            raise ValueError(case)
    with tempfile.TemporaryDirectory(prefix="campus-f-mysql-") as tmp:
        folder = Path(tmp)
        data = folder / "data"
        bootstrap = folder / "bootstrap.sql"
        port = free_port()
        pw = secrets.token_hex(24)
        base = [server, "--no-defaults", f"--basedir={bin_dir.parent}", f"--datadir={data}"]
        subprocess.run(base + ["--initialize-insecure"], cwd=folder, check=True, timeout=150)
        bootstrap.write_text(f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{pw}';\n", encoding="ascii")
        connection = ["--no-defaults", "--protocol=TCP", "-h127.0.0.1", f"-P{port}", "-uroot"]
        env = dict(os.environ, MYSQL_PWD=pw)
        def sql(statement, database=True, check=True):
            result = subprocess.run([client, *connection, "--default-character-set=utf8mb4", "-BN",
                                     *([DB] if database else [])], input=statement.encode(),
                                    capture_output=True, env=env, timeout=25)
            if check and result.returncode:
                raise RuntimeError(result.stderr.decode("utf-8", "replace"))
            return result
        def scalar(query):
            return sql(query).stdout.decode("utf-8").strip()
        proc = subprocess.Popen(base + [f"--port={port}", "--bind-address=127.0.0.1", "--mysqlx=OFF",
                                        f"--init-file={bootstrap}", f"--log-error={folder / 'server.err'}"], cwd=folder)
        try:
            for _ in range(80):
                if proc.poll() is not None:
                    raise RuntimeError((folder / "server.err").read_text(errors="replace")[-1800:])
                if sql("SELECT 1;", False, False).returncode == 0:
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("MySQL startup timed out")
            bootstrap.unlink()
            sql(f"CREATE DATABASE {DB} CHARACTER SET utf8mb4;", False)
            ddl = (SQL / "schema.sql").read_text(encoding="utf-8")
            seed = (SQL / "data.sql").read_text(encoding="utf-8")
            for i in (1, 2):
                sql(ddl)
                sql(seed)
                expect(f"DB0{i} tables", "4", scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"))
                expect(f"DB0{i} rows courses/enrollments/materials/chunks", "2/2/0/0", scalar(
                    "SELECT CONCAT_WS('/',(SELECT COUNT(*) FROM campus_course),(SELECT COUNT(*) FROM campus_course_enrollment),"
                    "(SELECT COUNT(*) FROM rag_material),(SELECT COUNT(*) FROM rag_chunk))"))
            expect("DB03 enrollment", "CS101\tstudent\nSE101\tstudent2", scalar(
                "SELECT course_code,student_username FROM campus_course JOIN campus_course_enrollment "
                "ON campus_course.id=course_id ORDER BY course_code").replace("\r\n", "\n"))
            for case, statement, error in [
                ("DB04 duplicate enrollment", "INSERT INTO campus_course_enrollment VALUES (1,'student')", "1062"),
                ("DB05 missing course", "INSERT INTO campus_course_enrollment VALUES (999,'student')", "1452"),
                ("DB06 referenced course", "DELETE FROM campus_course WHERE id=1", "1451")]:
                result = sql(statement, check=False)
                actual = result.stderr.decode(errors="replace")
                expect(case, error, error if result.returncode and "ERROR " + error in actual else actual)
            original = "INSERT INTO rag_material(id,course_id,filename,content,sha256,parse_status,version,parser_version) VALUES ('test',1,'test.txt',X'31',REPEAT('a',64),'READY',1,'test');"
            piece = "INSERT INTO rag_chunk(id,material_id,version,ordinal,text) VALUES ('sample','test',1,1,'abc');"
            result = scalar("START TRANSACTION;"+original+piece+"DELETE FROM rag_material WHERE id='test';"
                            "SELECT COUNT(*) FROM rag_chunk WHERE material_id='test';ROLLBACK;")
            expect("DB07 cascade", "0", result)
            if jar:
                import verify_http
                # Disposable account limited to the temporary project schema.
                app_pw = secrets.token_hex(24)
                sql(f"CREATE USER 'campus_app'@'127.0.0.1' IDENTIFIED BY '{app_pw}';"
                    f"GRANT ALL ON {DB}.* TO 'campus_app'@'127.0.0.1';", False)
                url = f"jdbc:mysql://127.0.0.1:{port}/{DB}?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC"
                verify_http.verify(repo, jar, folder, url, "campus_app", app_pw)
        finally:
            if proc.poll() is None:
                try:
                    subprocess.run([admin, *connection, "shutdown"], env=env, capture_output=True, timeout=20)
                    proc.wait(timeout=12)
                finally:
                    if proc.poll() is None:
                        proc.kill()
                        proc.wait()
    print(version, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bin", type=Path, required=True)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--repo", type=Path)
    args = parser.parse_args()
    if args.jar and not args.repo:
        parser.error("--jar requires --repo")
    check_mysql(args.bin.resolve(), args.jar and args.jar.resolve(), args.repo and args.repo.resolve())
