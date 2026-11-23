# RAG 接口约定

所有业务接口前缀为 `/campusai/courses/{courseId}/rag`，请求头使用 `Authorization: Bearer <token>`；令牌从现有 `POST /login` 获取。沿用现有 JSON 包装 `{code,msg,data}`，业务错误通常仍是 HTTP 200，调用者必须检查 `code`。文件下载成功返回二进制，错误仍返回 JSON。所有授权判断由服务端执行。

## 端点

| 方法/相对路径 | 输入 | 返回 data / 权限 |
| --- | --- | --- |
| `GET /materials` | 无 | 资料数组；学生只见 READY 且已发布资料，管理者见全部 |
| `GET /materials/{id}` | 无 | 资料状态；同上 |
| `POST /materials` | multipart `file` | 新建并同步解析；仅负责课程的教师/管理员 |
| `PUT /materials/{id}` | multipart `file` | 替换内容；变更后须重新发布；仅管理者 |
| `POST /materials/{id}/reprocess` | 无 | 重处理保存的文件；同内容成功状态幂等；仅管理者 |
| `PATCH /materials/{id}/publication` | `{"published":true}` 或 `false`，严格布尔值 | 发布/下架后资料状态；仅管理者 |
| `DELETE /materials/{id}` | 无 | 删除资料与片段；仅管理者 |
| `GET /materials/{id}/file` | 无 | 授权下载，`Cache-Control: no-store`；学生只能下载当前已发布资料 |
| `GET /search?q=...&topK=3` | q 非空、最多 500 字符；topK 1–10 | `SearchResult` |
| `GET /sources/{chunkId}` | 无 | 当前可见来源；失效、越权、旧版本均 code404 |
| `POST /ask` | `{"question":"残差连接有什么作用？"}`，严格字符串 | `Answer` |

`courseId` 使用当前课程表的数值 ID；`materialId/chunkId` 是不透明 UUID 字符串，不能猜测文件路径。即使 UUID 正确，也不能通过另一课程入口读取。

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
  "heading": null
}
```

PDF 只有 `pageNumber`；DOCX/TXT 的 `pageNumber=null`，使用从 1 开始的 `paragraphStart/paragraphEnd`。DOCX 标题可有 `heading`，TXT 不推断标题。定位对应解析规范化的原文与实际文件，不能把段落编号当页码。

`SearchResult` 为 `{"status":"ok","hits":[{"source":Source,"score":1.23,"coverage":0.75}]}`。`score` 是 BM25 分数，`coverage` 是问题词项覆盖比例。`status=no_evidence` 表示没有达到词面门槛的候选；`sources_changed` 表示候选在返回前失效，需要重新查询。这两个状态的 `hits` 为空。

`Answer`：

```json
{
  "status": "retrieval_only",
  "answer": "本次未生成回答，请查看下方相关原文并核对来源。",
  "citations": [],
  "evidence": [],
  "reason": "unconfigured"
}
```

上述是字段结构示例，实际回退响应的 `evidence` 包含真实 `SearchHit`。`generated` 时 `answer` 内的 `[1]` 对应 `citations[0]`，引用只由服务端从本次候选映射；前端必须按纯文本显示回答和原文，不能直接插入 HTML。

| status | 含义与界面行为 |
| --- | --- |
| `generated` | 返回了通过结构/引文校验的生成说明；仍需用户核对语义 |
| `retrieval_only` | 模型未配置、超时、服务错误或输出未通过校验；显示原文而不伪装生成成功 |
| `no_evidence` | 没有有效词面候选，或模型返回空声明承认候选不足；后者可保留相关原文但 citations 为空 |
| `sources_changed` | 模型等待期间或返回前资料变更；不返回旧回答、旧引用或旧原文，请重新提问 |

回退 `reason` 可能为 `unconfigured/timeout/provider_error/invalid_response/invalid_citation/interrupted`。模型承认不足时是 `insufficient_evidence`。请求本身非法返回 `code=400`，无权限课程、文件或来源返回 `404`，学生执行管理操作返回 `403`，发布未就绪资料返回 `409`。

## 并发和数据库交接

材料变更事务采用 READ_COMMITTED 和单资料行锁。这样 MySQL 默认 REPEATABLE_READ 的旧快照不会在等待其他更新后影响幂等重试返回。调用方若在外层已有事务，应也使用 READ_COMMITTED；不要在长生命周期事务中持有模型网络调用。问答编排本身不持有数据库事务，模型结束后重新读取课程权限及候选资格。

授权结果以最后一次校验读到的状态为准；响应一旦已交给客户端，不能撤回已读原文。下载/引用每次重新授权，没有永久公开 URL。A 保存历史后，其历史读取接口也需处理权限撤销及资料失效，不能仅复用历史存下来的原文而绕过授权。
