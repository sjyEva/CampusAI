"""Run the project API and RAG checks against a new local CampusAI process."""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
from contextlib import contextmanager

import requests

SAMPLES = Path(__file__).resolve().parent.parent / "02_初始化与样例" / "样例资料"


def verify(repo, jar, workspace, jdbc, user, db_password):
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    password = secrets.token_hex(20)
    env = {k: v for k, v in os.environ.items() if not k.upper().startswith(("CAMPUSAI_", "SPRING_"))
           and k.upper() not in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
    env.update(CAMPUSAI_PORT=str(port), CAMPUSAI_ADDRESS="127.0.0.1", CAMPUSAI_JDBC_URL=jdbc,
               CAMPUSAI_JDBC_USERNAME=user, CAMPUSAI_JDBC_PASSWORD=db_password, CAMPUSAI_DEV_PASSWORD=password)
    session = requests.Session()
    session.trust_env = False
    @contextmanager
    def backend():
        with (workspace / "backend.log").open("ab") as log:
            proc = subprocess.Popen(["java", "-jar", str(jar)], cwd=workspace, env=env, stdout=log, stderr=log)
            try:
                for _ in range(120):
                    if proc.poll() is not None:
                        raise RuntimeError("backend exited: " + (workspace / "backend.log").read_text(errors="replace")[-1200:])
                    try:
                        if session.get(base+"/rag-demo.html", timeout=1).status_code == 200:
                            break
                    except requests.ConnectionError:
                        pass
                    time.sleep(0.5)
                else:
                    raise RuntimeError("backend startup timeout")
                yield
            finally:
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait()
    def request(method, path, token=None, **kwargs):
        return session.request(method, base+path, headers={"Authorization":"Bearer "+token} if token else {},
                               timeout=35, **kwargs)
    def api(method, path, token=None, code=200, **kwargs):
        response = request(method, path, token, **kwargs)
        payload = response.json()
        if payload.get("code") != code:
            raise ValueError(f"{method} {path}: HTTP {response.status_code}, business code {payload.get('code')}, expected {code}")
        return payload
    def login(name):
        return api("POST","/login",json=dict(username=name,password=password))["token"]
    def check(case, value, detail):
        if not value:
            raise AssertionError(case+" | "+str(detail))
        print("PASS",case,"|",detail,flush=True)
    with backend():
        for script in ("verify-api.ps1", "verify-rag.ps1"):
            subprocess.run([shutil.which("pwsh") or "pwsh","-NoProfile","-File",str(repo/"scripts"/script),
                            "-BaseUrl",base],env=env,check=True,timeout=150)
        teacher, student, other = (login(name) for name in ("teacher","student","student2"))
        prefix = "/campusai/courses/1/rag"
        other_prefix = "/campusai/courses/2/rag"
        def upload(path):
            with path.open("rb") as f:
                item = api("POST",prefix+"/materials",teacher,files={"file":(path.name,f)})["data"]
            check("format "+path.suffix,item["parseStatus"]=="READY" and not item["published"] and item["chunkCount"]>0,
                  f"{path.name}: {item['parseStatus']}, draft, chunks={item['chunkCount']}")
            api("PATCH",prefix+f"/materials/{item['id']}/publication",teacher,json=dict(published=True,expectedVersion=1))
            return item["id"]
        for ext,query,quote,line in [
                ("pdf","How does BM25 rank text?", "BM25 ranks text chunks",1),
                ("docx","资料状态","解析成功且已发布",8),
                ("txt","物理行编号","TXT 按原始物理行编号定位",5)]:
            path = SAMPLES / ("parsing-demo."+ext)
            material = upload(path)
            hits = api("GET",prefix+"/search",student,params={"q":query})["data"]["hits"]
            source = next((hit["source"] for hit in hits if hit["source"]["materialId"]==material
                           and quote in hit["source"]["text"]),None)
            if source is None:
                raise AssertionError(f"{ext}: expected reference not found: {quote}")
            located = (source["pageNumber"]==line if ext=="pdf" else source["pageNumber"] is None and
                       source["paragraphStart"] <= line <= source["paragraphEnd"])
            check("source "+ext,located, f"page={source['pageNumber']}, paragraphs={source['paragraphStart']}..{source['paragraphEnd']}, quote={quote}")
            link = "/sources/"+source["chunkId"]
            check("open source "+ext, api("GET",prefix+link,student)["data"]["text"]==source["text"],"source text matches hit")
            download = request("GET", prefix+f"/materials/{material}/file",student)
            check("file "+ext,download.ok and download.content==path.read_bytes(),hashlib.sha256(download.content).hexdigest())
            for stem in (prefix, other_prefix):
                denied = api("GET",stem+link,other,404)
                check("course isolation "+ext,not denied.get("data"),"404 with no source; course "+stem.split("/")[3])
            if ext == "txt":
                fallback=api("POST",prefix+"/ask",student,json={"question":query})["data"]
                check("fallback",fallback["status"]=="retrieval_only" and fallback["reason"]=="unconfigured"
                      and any(h["source"]["materialId"]==material for h in fallback["evidence"]),
                      f"{fallback['status']}, reason={fallback['reason']}, verified evidence")
                missing=api("POST",prefix+"/ask",student,json={"question":"QZXNOTINCAMPUSFILE"})["data"]
                check("unknown term",missing["status"]=="no_evidence" and not missing["citations"] and not missing["evidence"],
                      missing["status"])
                kept = material, link, path
            else:
                api("DELETE",prefix+"/materials/"+material,teacher)
        old_session = student
    with backend():
        revoked=api("GET","/getInfo",old_session,401)
        check("restart session",not revoked.get("user"),"old session rejected, business code 401")
        student=login("student")
        material,link,path=kept
        source=api("GET",prefix+link,student)["data"]
        download=request("GET",prefix+f"/materials/{material}/file",student)
        check("restart persistence",source["materialId"]==material and download.ok and download.content==path.read_bytes(),
              "fresh login: same source and identical TXT bytes")
    session.close()


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo",required=True,type=Path)
    parser.add_argument("--jar",required=True,type=Path)
    args=parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="campus-f-h2-") as tmp:
        folder=Path(tmp)
        url="jdbc:h2:file:"+ (folder/"database").as_posix()+";MODE=MySQL;WRITE_DELAY=0"
        verify(args.repo.resolve(),args.jar.resolve(),folder,url,"sa","")
