# 杨宇轩 E：第二周解析、检索与引用交接

基于主分支 `39851812ff6d22c85ea6f105da10b19577cebbb4`，对应 2026-11-30 至 12-06 的第二周计划。本目录交付 E 的服务端实现、接口样例和可复跑验收；正式后台、小程序接入及全组共享环境回签由相关成员完成，不以独立模块验证替代联合验收。

## 本周接口

前缀 `/campusai/courses/{courseId}/rag`，沿用 Bearer 登录与 `{code,msg,data}`。

| 接入方 | 接口与行为 | 核对内容 |
| --- | --- | --- |
| B 陈用礼 | 上传 multipart 增加可选 `chapterId`；资料列表支持 `?chapterId=102`；`PATCH /materials/{id}/chapter` 绑定或清除章节 | 资料 UUID 与内容版本保持；章节属于路径课程；替换正文保留章节；继续使用唯一的 `rag_material.published` |
| C 张涵 | `GET /materials/{id}/preview?version=1&offset=0&limit=12`；现有 `/search` 和 `/sources/{chunkId}` | 按返回 `nextOffset` 续读；显示真实页/段落/行/幻灯片/表格位置；重新进入及 404/409 后清除旧正文 |
| D 卢冰玲 | 复用上传、详情、重处理、替换、发布/下架，写入携带版本 | `code=200` 但 `parseStatus=FAILED` 必须显示 `errorMessage`；不能发布；409 后刷新并重新确认 |
| F 洪少敏 | 新增章节关联表、自编样例、固定有依据/无依据问题和验收脚本 | 保留旧资料与选课；在共享测试提交上复跑；另行覆盖两位真实教师、新注册学生和两端页面 |
| A 思佳怡 | 合并章节/预览与真实流式改动，核对认证及 PR #8 | `/ask` 保持兼容；两端尚未接 SSE 时仍可正常使用；不要同时保留两个相同预览路由 |

绑定示例：`PATCH /materials/{id}/chapter`，JSON `{"chapterId":102,"expectedVersion":1}`；清除关联时显式传 `chapterId:null`。旧上传不带章节参数仍有效。章节调整不重建知识片段，也不触发再次调用模型。

## 迁移与兼容

新增 `rag_material_chapter(material_id,chapter_id)`，原资料表、文件字节、来源 ID 和内容版本均不改动。默认启动执行 `schema.sql` 会补建表；关闭自动初始化的环境，在升级后端前执行 `backend/src/main/resources/db/migration/V20261127__rag_material_chapter.sql`，前置表为 `rag_material` 和 `campus_course_chapter`。脚本可重复执行，旧资料保留 `chapterId:null`。

应用层在事务中锁定资料及目标章节，检查课程归属后绑定；删除被关联章节会被外键阻止，应先重新分配资料。B 后续的章节写接口应保持章节所属课程不变，不能通过直接修改 `course_id` 绕过资料归属约束。`expectedVersion` 是内容版本保护；章节元数据的并发修改以后写为准。实际 MySQL 环境仍由 F 在共享部署时复验，本轮本机验证使用 H2 的 MySQL 模式。

## 固定样例与复跑

`samples/` 为开发者自编材料，不使用老师课件。`lesson.pdf/docx/txt` 内容一致，有依据问题为 `residual connections`，无依据问题为 `qubit entanglement`；替换资料的问题为 `software testing`。PDF 命中第 1 页，DOCX/TXT 起始段落/物理行为 1。文件 SHA256 和题目见 [cases.json](cases.json)。`broken.pdf` 故意损坏，用于验证可入库失败状态。

在独立测试课程执行，确保该课程没有覆盖无依据问题的其他已发布资料。脚本默认课程 1、章节 102；可通过参数改为共享环境的课程和章节。

```powershell
# Python 需要 requests；密码从当前终端环境读取，不写进命令参数或结果。
# 可用 CAMPUSAI_DEV_PASSWORD；独立账号密码优先使用
# CAMPUSAI_TEACHER_PASSWORD / CAMPUSAI_STUDENT_PASSWORD / CAMPUSAI_STUDENT2_PASSWORD。
python scripts/verify-rag-week2.py --base-url http://127.0.0.1:8080 --output backend/target/week2-http.json

# 成功后再保留三份新发布样例，供 C/D/F 用同一 UUID 与版本交叉核对。
python scripts/verify-rag-week2.py --base-url http://127.0.0.1:8080 --output backend/target/week2-demo.json --keep
```

脚本不调用模型，检查三格式真实上传、章节列表、发布、搜索、位置、原文、预览、重复处理、下架、替换、版本冲突、删除、跨课程拒绝，以及损坏文件重试与修复。默认只清理本次创建的资料；成功使用 `--keep` 时保留三份最终样例，结果中记录 UUID、版本、来源和每步预期/实际。结果不包含令牌或密码。模型流式协议另见 [API 文档](../api.md#流式问答)。

## PR #8 预览评审与合并

已检查 PR #8 的预览协议与涉及的后端差异，本次保持相同路径、请求参数、`sources/nextOffset` 返回结构，不替代 C 的学生端工作。本轮加强：读取后始终复查权限/发布/版本；`nextOffset` 取最后实际片段序号，避免分页缺口与加法溢出；预览成功响应 `Cache-Control: no-store`；补齐七种扩展名位置及请求中撤权/下架/替换的回归。

预览端点保留在原 `RagController` 中，数据读取委托 `RagPreviewService/Repository`。PR #8 合并时，保留这一份端点，勿叠加另一个同路径 Controller；若先合 #8，再合本分支，应核对该方法使用加强后的服务。PR #8 中旧的 `RagMaterialService.preview`、`RagRepository.preview` 若成为未使用代码，可在解决合并冲突时删除。本轮未将 C 的全部页面改动合入，不宣称微信真机或正式双端已经验收。

## 本人交付与组内验收边界

本轮服务端测试、HTTP 复跑结果见 [verification.md](../verification.md)。提交/PR 应附该结果与本文件。组员接入后，按第二周计划由 D 发布同一资料、C 按章节读取及搜索、D 下架、F 重新打开旧预览/引用验证拒绝，并记录同一课程、章节、UUID、版本和 Git 提交。账号注册、加课、收藏持久化、两端视觉样式及共享环境部署分别保留 A/B/C/D/F 的主责。
