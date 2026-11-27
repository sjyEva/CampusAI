# RAG 接口约定

所有业务接口前缀为 `/campusai/courses/{courseId}/rag`，请求头使用 `Authorization: Bearer <token>`；令牌从现有 `POST /login` 获取。普通接口沿用 JSON 包装 `{code,msg,data}`，业务错误通常仍是 HTTP 200，调用者必须检查 `code`。文件下载成功返回二进制，流式问答成功返回 SSE；建立流之前的错误仍返回 JSON。所有授权判断由服务端执行。

## 端点

| 方法/相对路径 | 输入 | 返回 data / 权限 |
| --- | --- | --- |
| `GET /capabilities` | 无 | 当前课程授权下的模型配置状态、问答等待上限；不返回密钥/模型名/服务地址 |
| `GET /materials` | 可选 `chapterId` | 资料数组；学生只见 READY 且已发布资料，管理者见全部；章节必须属于当前课程 |
| `GET /materials/{id}` | 无 | 资料状态；同上 |
| `POST /materials` | multipart `file`，可选 `chapterId` | 新建并同步解析；仅负责课程的教师/管理员；无效章节拒绝且不创建资料 |
| `PATCH /materials/{id}/chapter` | `{"chapterId":102,"expectedVersion":1}`；两字段必填，`chapterId:null` 清除关联 | 同课程章节归属；仅管理者；保留资料 UUID、内容版本和引用 |
| `PUT /materials/{id}` | multipart `file`；query `version` | 替换内容；变更后须重新发布；仅管理者 |
| `POST /materials/{id}/reprocess` | query `version` | 重处理保存的文件；同内容成功状态幂等；仅管理者 |
| `PATCH /materials/{id}/publication` | `{"published":true,"expectedVersion":1}`，严格布尔值和正整数 | 发布/下架后资料状态；仅管理者 |
| `DELETE /materials/{id}` | query `version` | 删除资料与片段；仅管理者 |
| `GET /materials/{id}/file` | query `version` | 授权下载，`Cache-Control: no-store`、`X-Material-Version`；学生只能下载当前已发布资料 |
| `GET /materials/{id}/preview` | 必填 `version`，可选 `offset=0&limit=12`；limit 1–30 | `{sources: Source[], nextOffset: number|null}`；所有角色均只读取当前已发布且 READY 版本；响应 `no-store` |
| `GET /search?q=...&topK=3` | q 非空、最多 500 字符；topK 1–10 | `SearchResult` |
| `GET /sources/{chunkId}` | 无 | 当前可见来源；失效、越权、旧版本均 code404 |
| `POST /ask` | `{"question":"残差连接有什么作用？"}`，严格字符串 | `Answer` |
| `POST /ask/stream` | 同 `/ask`；`Accept: text/event-stream` | SSE：状态、实时文本增量、最终 `Answer` 或错误 |

`courseId` 使用当前课程表的数值 ID；`materialId/chunkId` 是不透明 UUID 字符串，不能猜测文件路径。即使 UUID 正确，也不能通过另一课程入口读取。

上传和替换仍是单文件 multipart `file`，支持 `.pdf/.docx/.txt/.ppt/.pptx/.xls/.xlsx`，大小上限 10 MB。后端按文件实际类型校验，不能只改扩展名；具体文本提取范围、公式缓存和容量限制见 [模块说明](README.md#解析索引与状态)。

资料列表或引用显示 v1 时，写操作和下载应传对应的 `version=1`；发布/下架通过 JSON 的 `expectedVersion` 传入。锁行后的当前版本不符返回 `code=409`，调用方刷新资料并让用户核对，不能自动重试写操作。版本字段为兼容旧客户端可省略；省略仍按当前版本执行，**不具备防止旧页面误操作的保护**。演示页已对所有这些操作传版本。下载在一次查询取得的状态、版本和文件字节上校验，避免旧引用跳到新文件；不保存历史文件版本。

能力示例：`{"generationConfigured":false,"answerTimeoutSeconds":45}`。`generationConfigured=true` 只表示已填入模型地址及名称，不证明服务健康。问答前端超时使用 `answerTimeoutSeconds`（至少 45 秒，模型上限加 15 秒余量，最大 75 秒）；能力读取失败可使用 75 秒及中性状态，不应把服务状态误写成未配置。普通查询和上传保留各自等待上限。

## 第二周：章节关联与正文预览

`Material` 增加可空的 `chapterId`，原字段含义不变。省略上传的 `chapterId` 时保留原行为；历史资料也保持未分配，不自动猜测章节。按章节列资料仍受课程、角色及发布状态过滤。章节不存在或属于其他课程返回业务 404，不创建上传记录。

教师可用 `PATCH /materials/{id}/chapter` 设置或清除关联，必须显式传 `chapterId`（正整数或 null）和正整数 `expectedVersion`。绑定只修改业务元数据，不改变文件内容、资料 UUID、内容版本、片段 ID 或发布状态；替换正文后保留章节关联。`expectedVersion` 防止旧内容页面操作新内容，不是章节元数据的并发版本：对同一内容版本的并发章节修改串行执行，以后写为准。删除已有资料会级联清除关联；有资料关联的章节必须先重新分配资料再删除。

预览请求示例：`GET /materials/{id}/preview?version=1&offset=0&limit=12`。`offset` 是已经读取到的片段序号，首次为 0；后续原样使用 `nextOffset`，不要自行累加。按资料内真实序号返回，`nextOffset=null` 表示结束。响应为 `{code:200,msg:"操作成功",data:{sources:[...],nextOffset:null}}`，Source 与检索和引用接口一致。`version` 必填，其他参数越界返回 400；未发布、失败、下架、删除、越权返回 404；已发布资料的内容版本不符返回 409。读取正文后再次检查权限和资料版本，成功响应禁止缓存。

客户端重新打开资料、切换用户、收到 404/409 或退出登录时，应清除旧预览和来源正文，再从资料详情取当前状态；HTTP 200 的业务错误同样必须处理。预览展示解析文本及位置，并非 Office 原文件版式渲染。PDF 的 `pageNumber`、DOCX 的段落区间和 TXT 的物理行区间应按文件类型标注；不要把所有类型统一写成页码。

可复跑脚本、三种自编样例、固定问题和迁移/PR #8 交接说明见 [第二周交接](week2/README.md)。

## 流式问答

`/ask` 保留原有完整 JSON 行为。演示页的默认提问使用 `/ask/stream`，后端向模型发送 `stream:true`，逐帧读取提供方返回的内容。前端通过 `fetch` 和 `ReadableStream` 读取 POST 响应；原生 `EventSource` 不能直接满足此 POST 和 Bearer 请求头契约。

| SSE event | JSON data | 客户端行为 |
| --- | --- | --- |
| `status` | `{"stage":"retrieving"}`、`generating`、`validating` | 显示检索、等待模型或核对状态 |
| `delta` | `{"text":"新到达的说明文字"}` | 立即追加纯文本，标明“引用待校验”；不视为已完成答案 |
| `done` | 裸 `Answer` 对象，不套 `{code,msg,data}` | 用最终结果替换全部草稿；仅 `generated` 的已校验引用可供核对 |
| `error` | `{"code":409,"message":"资料状态已变化，请重新提问。"}` | 清空草稿并提示；401 时退出当前登录 |

`delta` 只包含本次模型结构化输出中 `claims[].text` 的实际增量，不含内部证据 ID、引文 JSON 或思考内容，也没有本地打字延迟。完整输出仍进行结构、引用和资料版本校验：模型错误、超时或无效引用通过最终回退结果替换草稿；读取到 EOF 却未收到 `done`、解析失败、用户取消或课程切换时，客户端必须丢弃草稿。历史记录应只保存最终 `done`，不能把中间文本当成通过校验的回答。

模型的总读取时间受配置的 1–60 秒上限约束，流字节上限 512 KiB，最终模型 JSON 最多 20,000 个 UTF-16 字符。客户端继续使用能力接口的 `answerTimeoutSeconds`。服务最多同时执行 4 个流请求，无等待队列，满载返回业务 code429。每次发送文字前及完成时重新核对课程权限和资料当前版本；客户端断开或取消时释放模型连接。已发送且已被用户看到的文本无法追溯撤回，因此最终核对失败后须明确清除草稿。

代理部署应关闭 SSE 缓冲并允许长连接；后端发送 `Cache-Control: no-store, no-transform` 和 `X-Accel-Buffering: no`。新增的 `/rag-stream.js` 是不含凭证的静态解析器，可匿名加载。

## 数据结构

资料解析失败是入库业务结果：`code=200`，但 `data.parseStatus=FAILED`，需展示 `errorMessage`。只有 `READY` 才可发布，不能仅看 HTTP/JSON 成功就声称解析成功。

```json
{
  "id": "资料UUID",
  "courseId": 1,
  "chapterId": 102,
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
