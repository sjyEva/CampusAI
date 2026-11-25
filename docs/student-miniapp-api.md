# 学生小程序接口与演示数据

学生端使用 uni-app。未登录时进入本机演示，个人中心的「连接后端」可切换到真实学生账号。已登录时，课程及 RAG 请求来自后端。后端失败时页面显示错误，不会将演示课程冒充真实结果。

## 微信开发者工具

在仓库根目录执行以下命令，导入 `student-miniapp/dist/dev/mp-weixin`。团队 AppID 通过本机忽略文件 `student-miniapp/src/project.config.json` 配置。设置方法见 [运行指南](run-guide.md#51-微信小程序)。

```powershell
Set-Location student-miniapp
npm.cmd ci
npm.cmd run dev:mp-weixin
```

- 默认首页提供 7 门自编课程，并带待办、笔记、收藏和反馈样例。软件工程包含需求分析笔记、PDF 与 PPT/PPTX 课件，问答中的示例「课件里怎样核对引用？」会打开第 2 张幻灯片。
- 连接真实后端时，可使用 [运行指南中的学生账号](run-guide.md#3-启动后端)。课程资料须由有权限的教师或管理员发布后才可见。
- 微信「导入资料」从聊天会话选择文件，TXT 和 Markdown 同样由此导入。「写笔记」用于手动填写。PDF、Office 原件使用微信的文档查看器，导出文件由用户在微信界面完成。文件选择、原件查看和导出在真机上的可用性由微信客户端决定。
- 演示 PDF/PPT/PPTX 可在资料页翻阅，真实课程资料使用同页文本预览，支持悬浮问答。引用跳转到资料页时，会展示对应的页、幻灯片、表格或段落及原文片段；查看文件版式可另开原件。
- 真实设备需配置可达的服务地址和合规域名。构建时以 `VITE_CAMPUSAI_API_BASE_URL` 指定地址，开发者工具本机默认使用 `http://127.0.0.1:8080`。

## HTML 原型

从仓库根目录运行 `node prototypes/student-miniapp/serve.mjs`，在浏览器打开 `http://127.0.0.1:5180/`。演示模式可直接查看手机布局。启用原型内的后端联调时，预览服务默认转发至本机 `http://127.0.0.1:8080`。

## 已接入的 HTTP 接口

登录接口遵循 [共同接口约定](run-guide.md#63-共同接口约定)，RAG 接口遵循 [RAG 接口文档](rag/api.md)。除公开登录相关接口外，请求带 `Authorization: Bearer <token>`。客户端校验 HTTP 状态和 JSON 业务码。JSON 成功响应的业务码为 200。原文件下载成功返回二进制，错误返回 JSON。

| 方法和路径 | 用途与关键字段 |
| --- | --- |
| `GET /captchaImage`、`POST /login`、`GET /getInfo`、`POST /logout` | 登录、校验学生角色和退出 |
| `GET /campusai/courses` | 已选课程列表，字段为 `id/courseCode/name/description` |
| `GET /campusai/courses/{courseId}` | 校验课程可见范围并读取课程简介 |
| `GET /campusai/courses/{courseId}/rag/materials` | 当前课程已发布且 READY 的资料 |
| `GET /campusai/courses/{courseId}/rag/materials/{id}` | 检查资料状态及版本 |
| `GET /campusai/courses/{courseId}/rag/materials/{id}/preview` | **新增**。按文档顺序分页返回解析片段 |
| `GET /campusai/courses/{courseId}/rag/materials/{id}/file?version=1` | 读取具有访问权限且版本匹配的原文件 |
| `POST /campusai/courses/{courseId}/rag/ask` | 提交 `{ "question": "…" }`，区分生成回答、仅检索、无证据及资料变更 |
| `GET /campusai/courses/{courseId}/rag/sources/{chunkId}` | 点开引用时重新校验片段权限，核对资料 ID 和版本 |

### 资料内容预览

请求参数 `version` 为正整数、必填。`offset` 从 0 开始，默认 0。`limit` 默认 12，范围 1–30。响应 `data.sources` 是按文档顺序排列的 [Source](rag/api.md#数据结构) 数组，`data.nextOffset` 为下一页偏移，读完为 `null`。

例如 `GET /campusai/courses/2/rag/materials/{id}/preview?version=1&offset=0&limit=12`。

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "sources": [
      {
        "chunkId": "片段UUID",
        "materialId": "资料UUID",
        "courseId": 2,
        "version": 1,
        "filename": "需求讲义.pdf",
        "text": "响应时间约束描述系统的性能。",
        "pageNumber": 2
      }
    ],
    "nextOffset": null
  }
}
```

参数非法返回 400，课程无权、资料不存在或未发布返回 404，已发布资料版本不符返回 409。资料重新发布或下架后，客户端重新打开资料才能看到当前状态。

## 本地模拟接口

下表是小程序内的 JS 接口，定义于 `student-miniapp/src/api/workspace.js`。它们使用已校验身份的 `session`，以 `CampusAI-Workspace-v1:{owner}` 为本机存储键。演示身份和每个真实学生分别存储。同步写入接口返回更新后的工作区，失败时抛出带可读消息的 `Error`。

| 方法 | 参数与行为 |
| --- | --- |
| `getWorkspace(session)` | 读取 `profile/todos/collapsed/library/favorites/feedback` |
| `setSection(session, section, collapsed)` | 保存 `todo/courses` 的收起状态 |
| `saveTodo(session, { id?, text?, done? })` | 无 ID 新建，有 ID 更新内容或勾选，正文 1–64 字 |
| `moveTodo(session, id, direction)`、`removeTodo(session, id)` | 按 `-1/1` 上下移动，移除指定待办 |
| `saveProfile(session, { nickname, avatar })` | 保存 1–20 字昵称和本机头像 |
| `savePersonalEntry(session, form, picked?)` | 异步新增或编辑资料。`form` 含 `id?/updatedAt?/kind/name/courseId/courseName/category/text?`，导入或替换时传已选文件。返回保存记录，编辑保留 ID，并检查 `updatedAt` |
| `setFavorite(session, entry, enabled)` | 按资料来源、ID 及发布版本去重。收藏中个人资料始终读取最新本机记录 |
| `submitFeedback(session, course, kind, content)` | 保存 8–500 字的课程反馈，个人中心可查看历史详情 |

导入的数据与反馈目前仅保存在设备端。教师管理端不会收到本机反馈，个人导入文件也不会进入课程的 RAG 索引。暂不需要后端表或网络上传接口，正式同步可在这组契约基础上对接业务服务。

## 样例来源

课程、待办、反馈与纯文本笔记为演示数据。PDF、PPT、PPTX、DOCX 原件来自项目的 [RAG 自编资料](rag/samples/README.md)，翻页图由其中的 PDF/PPT/PPTX 转换。演示文件与真实课程权限互相隔离。
