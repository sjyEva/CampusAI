# RAG 接口约定

所有业务接口前缀为 `/campusai/courses/{courseId}/rag`，请求头使用 `Authorization: Bearer <token>`；令牌从现有 `POST /login` 获取。沿用现有 JSON 包装 `{code,msg,data}`，业务错误通常仍是 HTTP 200，调用者必须检查 `code`。文件下载成功返回二进制，错误仍返回 JSON。所有授权判断由服务端执行。

## 端点

| 方法/相对路径 | 输入 | 返回 data / 权限 |
| --- | --- | --- |
| `GET /capabilities` | 无 | 当前课程授权下的模型配置状态、问答等待上限；不返回密钥/模型名/服务地址 |
| `GET /materials` | 无 | 资料数组；学生只见 READY 且已发布资料，管理者见全部 |
| `GET /materials/{id}` | 无 | 资料状态；同上 |
| `POST /materials` | multipart `file` | 新建并同步解析；仅负责课程的教师/管理员 |
| `PUT /materials/{id}` | multipart `file`；query `version` | 替换内容；变更后须重新发布；仅管理者 |
| `POST /materials/{id}/reprocess` | query `version` | 重处理保存的文件；同内容成功状态幂等；仅管理者 |
| `PATCH /materials/{id}/publication` | `{"published":true,"expectedVersion":1}`，严格布尔值和正整数 | 发布/下架后资料状态；仅管理者 |
| `DELETE /materials/{id}` | query `version` | 删除资料与片段；仅管理者 |
| `GET /materials/{id}/file` | query `version` | 授权下载，`Cache-Control: no-store`、`X-Material-Version`；学生只能下载当前已发布资料 |
| `GET /materials/{id}/preview` | 必填 `version`，可选 `offset=0&limit=12`，`limit` 1–30 | 按资料内顺序返回 `{sources: Source[], nextOffset: number|null}`，只读取有权限课程中当前已发布并 READY 的版本 |
| `GET /search?q=...&topK=3` | q 非空、最多 500 字符；topK 1–10 | `SearchResult` |
| `GET /sources/{chunkId}` | 无 | 当前可见来源；失效、越权、旧版本均 code404 |
| `POST /ask` | `{"question":"残差连接有什么作用？"}`，严格字符串 | `Answer` |

`courseId` 使用当前课程表的数值 ID；`materialId/chunkId` 是不透明 UUID 字符串，不能猜测文件路径。即使 UUID 正确，也不能通过另一课程入口读取。

上传和替换仍是单文件 multipart `file`，支持 `.pdf/.docx/.txt/.ppt/.pptx/.xls/.xlsx`，大小上限 10 MB。后端按文件实际类型校验，不能只改扩展名；具体文本提取范围、公式缓存和容量限制见 [模块说明](README.md#解析索引与状态)。

资料列表或引用显示 v1 时，写操作和下载应传对应的 `version=1`；发布/下架通过 JSON 的 `expectedVersion` 传入。锁行后的当前版本不符返回 `code=409`，调用方刷新资料并让用户核对，不能自动重试写操作。版本字段为兼容旧客户端可省略；省略仍按当前版本执行，**不具备防止旧页面误操作的保护**。演示页已对所有这些操作传版本。下载在一次查询取得的状态、版本和文件字节上校验，避免旧引用跳到新文件；不保存历史文件版本。

能力示例：`{"generationConfigured":false,"answerTimeoutSeconds":45}`。`generationConfigured=true` 只表示已填入模型地址及名称，不证明服务健康。问答前端超时使用 `answerTimeoutSeconds`（至少 45 秒，模型上限加 15 秒余量，最大 75 秒）；能力读取失败可使用 75 秒及中性状态，不应把服务状态误写成未配置。普通查询和上传保留各自等待上限。

## 数据结构

资料解析失败是入库业务结果：`code=200`，但 `data.parseStatus=FAILED`，需展示 `errorMessage`。只有 `READY` 才可发布，不能仅看 HTTP/JSON 成功就声称解析成功。

```json
{
  "id": "资料UUID",
  "courseId": 1,
  "filename": "lecture.pdf",
  "parseStatus": "READY",
  "published": false,
  "version": 1,
  "chunkCount": 3,
  "errorMessage": null
}
```

`Source`：

```json
{
  "chunkId": "片段UUID",
  "materialId": "资料UUID",
  "courseId": 1,
  "version": 1,
  "filename": "lecture.pdf",
  "text": "来自资料的实际文本",
  "pageNumber": 2,
  "paragraphStart": null,
  "paragraphEnd": null,
  "heading": null,
  "slideNumber": null,
  "sheetName": null,
  "cellRange": null
}
```

PDF 只有 `pageNumber`；DOCX/TXT 的 `pageNumber=null`，使用从 1 开始的 `paragraphStart/paragraphEnd`。DOCX 标题可有 `heading`，TXT 不推断标题。定位对应解析规范化的原文与实际文件，不能把段落编号当页码。

PPT/PPTX 使用 `slideNumber`（原文件实际从 1 开始的幻灯片序号），例如 `slideNumber: 3` 显示“第 3 张幻灯片”。XLS/XLSX 使用 `sheetName` 与 `cellRange`，例如 `sheetName: "检索评估", cellRange: "B3:D8"` 显示“工作表「检索评估」· B3:D8”。Excel 正文含逐单元格地址，范围是包围范围而不是承诺区域中每个格子都有正文；长单元格拆分后的多个片段可有相同地址。PPT 与 Excel 的 `pageNumber/paragraphStart/paragraphEnd` 均为空。

三个 Office 位置字段为可选扩展，旧资料和其他格式返回 `null`，旧字段未改义。前端建议依次判断幻灯片、工作表、PDF 页码、段落位置，均无值时显示“原文片段”；按纯文本插入工作表名和正文。`search`、`sources`、`ask.citations` 和 `ask.evidence.source` 共享此结构，逐字引文仍以 `Source.text` 为准。位置字段仅提供核对位置，Office 在线预览及跳转由正式前端另行集成，下载继续携带 `version`。

`SearchResult` 为 `{"status":"ok","hits":[{"source":Source,"score":1.23,"coverage":0.75}]}`。`score` 是 BM25 分数，`coverage` 是问题词项覆盖比例。`status=no_evidence` 表示没有达到词面门槛的候选；`sources_changed` 表示候选在返回前失效，需要重新查询。这两个状态的 `hits` 为空。

`Answer`：

```json
{
  "status": "retrieval_only",
  "answer": "本次未生成回答，请查看下方相关原文并核对来源。",
  "citations": [],
  "evidence": [],
  "reason": "unconfigured",
  "claims": []
}
```

上述是字段结构示例，实际回退响应的 `evidence` 包含真实 `SearchHit`。`generated` 时 `answer` 内的 `[1]` 对应 `citations[0]`，引用只由服务端从本次候选映射；前端必须按纯文本显示回答和原文，不能直接插入 HTML。

`generated` 还返回逐条声明 `claims`，各项为 `{"text":"说明正文","citationNumber":1,"chunkId":"片段UUID","quote":"经校验的逐字引文","quoteStart":0,"quoteEnd":8}`。索引是原片段 `text` 的 UTF-16 左闭右开范围，Java 的 `substring` 和 JavaScript 的 `slice` 可直接核对；不是页内字符坐标。重复引文定位到第一次出现，不拆开 Unicode 代理对。引文至少四个 Unicode 码点，任一声明不合法时整次回退，所有 `claims` 为空。前端展示每条说明与引文，展开来源时重新授权并核对 `fresh.text.slice(quoteStart,quoteEnd)===quote` 后才高亮。引文可核对仍不代表说明获得语义支持。

| status | 含义与界面行为 |
| --- | --- |
| `generated` | 返回了通过结构/引文校验的生成说明；仍需用户核对语义 |
| `retrieval_only` | 模型未配置、超时、服务错误或输出未通过校验；显示原文而不伪装生成成功 |
| `no_evidence` | 没有有效词面候选，或模型返回空声明承认候选不足；后者可保留相关原文但 citations 为空 |
| `sources_changed` | 模型等待期间或返回前资料变更；不返回旧回答、旧引用或旧原文，请重新提问 |

回退 `reason` 可能为 `unconfigured/timeout/provider_error/invalid_response/invalid_citation/interrupted`。模型承认不足时是 `insufficient_evidence`。请求本身非法返回 `code=400`，无权限课程、文件或来源返回 `404`，学生执行管理操作返回 `403`，发布未就绪资料返回 `409`。

## 并发和数据库交接

新增 `rag_chunk_location(chunk_id, slide_number, sheet_name, cell_range)`，`chunk_id` 为主键并外键关联 `rag_chunk(id) ON DELETE CASCADE`。启动脚本自动补建，查询使用 LEFT JOIN 兼容旧片段；更换资料、重处理或删除会在原事务中删除对应位置。正式环境的 F 需先落地同一建表语句并验证 MySQL，不要将幻灯片或单元格位置塞入 PDF/段落字段。当前解析契约版本 `office-v2-smartcn-v1`，旧解析版本主动重处理会递增版本并撤下，需核对后重新发布。

材料变更事务采用 READ_COMMITTED 和单资料行锁。这样 MySQL 默认 REPEATABLE_READ 的旧快照不会在等待其他更新后影响幂等重试返回。调用方若在外层已有事务，应也使用 READ_COMMITTED；不要在长生命周期事务中持有模型网络调用。问答编排本身不持有数据库事务，模型结束后重新读取课程权限及候选资格。

授权结果以最后一次校验读到的状态为准；响应一旦已交给客户端，不能撤回已读原文。下载/引用每次重新授权，没有永久公开 URL。A 保存历史后，其历史读取接口也需处理权限撤销及资料失效，不能仅复用历史存下来的原文而绕过授权。
