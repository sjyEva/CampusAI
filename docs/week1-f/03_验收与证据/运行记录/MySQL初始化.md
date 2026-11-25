# MySQL初始化

本次执行的输出如下。

```text
{"case": "DB01 tables", "expected": "4", "actual": "4", "passed": true}
{"case": "DB01 rows courses/enrollments/materials/chunks", "expected": "2/2/0/0", "actual": "2/2/0/0", "passed": true}
{"case": "DB02 tables", "expected": "4", "actual": "4", "passed": true}
{"case": "DB02 rows courses/enrollments/materials/chunks", "expected": "2/2/0/0", "actual": "2/2/0/0", "passed": true}
{"case": "DB03 enrollment", "expected": "CS101\tstudent\nSE101\tstudent2", "actual": "CS101\tstudent\nSE101\tstudent2", "passed": true}
{"case": "DB04 duplicate enrollment", "expected": "1062", "actual": "1062", "passed": true}
{"case": "DB05 missing course", "expected": "1452", "actual": "1452", "passed": true}
{"case": "DB06 referenced course", "expected": "1451", "actual": "1451", "passed": true}
{"case": "DB07 cascade", "expected": "0", "actual": "0", "passed": true}
C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe  Ver 8.0.46 for Win64 on x86_64 (MySQL Community Server - GPL)
```
