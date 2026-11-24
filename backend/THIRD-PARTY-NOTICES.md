# Backend source reference

The RAG module additionally uses Apache PDFBox 3.0.6, Apache POI 5.4.1 and
Apache Lucene SmartChineseAnalyzer 9.12.3 as Maven dependencies. They are
distributed under the Apache License 2.0; their original LICENSE/NOTICE files
remain in the dependency JARs. PDFBox's commons-logging dependency is excluded
in favor of Spring's existing spring-jcl bridge.

- https://pdfbox.apache.org/
- https://poi.apache.org/
- https://lucene.apache.org/
- https://www.apache.org/licenses/LICENSE-2.0

`AuthController` adapts the login, profile and route response contract from
[`SysLoginController.java`](https://gitee.com/y_project/RuoYi-Vue/blob/springboot3/ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysLoginController.java)
in [RuoYi-Vue `springboot3`](https://gitee.com/y_project/RuoYi-Vue/tree/springboot3).
The upstream branch was viewed at `a51330f`. This backend provides a local
implementation for the first-week integration and does not include the full
RuoYi backend. Its session storage, demo identities and course authorization are
implemented in CampusAI.

RuoYi-Vue [upstream license](https://gitee.com/y_project/RuoYi-Vue/blob/springboot3/LICENSE)

```text
The MIT License (MIT)

Copyright (c) 2018 RuoYi

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
