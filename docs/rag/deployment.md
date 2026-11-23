# 私有模型配置与组内公网联调

访问链路为「组员浏览器 → HTTPS 公网入口 → 本机 CampusAI 后端 → DeepSeek」。组员只使用 CampusAI 登录账号，前端只保存项目会话令牌；模型 API key 不进入前端、接口响应或 Git。后端会将本次问题和最多三个授权课程片段发给所配置的模型服务。

## 本机配置

Windows PowerShell 7.4 及以上可执行 `scripts/set-rag-model.ps1`，在隐藏输入提示中填写密钥。脚本使用 Windows 当前用户 DPAPI 加密，将配置保存到 `%LOCALAPPDATA%/CampusAI/secrets/model.json`，目录 ACL 仅允许当前用户和 SYSTEM；实际密钥不要作为命令行明文参数传入。

默认提供方 `https://api.deepseek.com`，模型 `deepseek-flash`，等待 45 秒，开启 JSON object 输出及 `thinking.type=disabled`。两个扩展参数在 Java 适配器默认关闭，其他 OpenAI 兼容提供方不必接受 DeepSeek 专用字段。当前官方模型及参数依据：[首次调用](https://api-docs.deepseek.com/)、[请求参数](https://api-docs.deepseek.com/api/create-chat-completion)、[JSON 输出](https://api-docs.deepseek.com/guides/json_mode)。

启动脚本从本机加密文件读取密钥，只通过 Java 子进程环境传入，不放入进程命令行。项目不自动加载 `.env`。拥有同一 Windows 账号或管理员权限的人仍可能读取解密后的运行环境；此方案保护的是普通网页访问者和仓库使用者，不提供对主机管理员的隔离。

## 公开演示账号

`CAMPUSAI_PUBLIC_DEMO=true` 时，四个种子账号必须分别配置至少 16 字符且互不相同的密码，且不能等于开发密码；不满足时后端拒绝启动。

| 账号 | 环境变量 | 范围 |
| --- | --- | --- |
| admin | `CAMPUSAI_ADMIN_PASSWORD` | 演示管理员，仅由负责人保管 |
| teacher | `CAMPUSAI_TEACHER_PASSWORD` | 负责课程的资料管理 |
| student | `CAMPUSAI_STUDENT_PASSWORD` | 已选计算机导论课程 |
| student2 | `CAMPUSAI_STUDENT2_PASSWORD` | 已选软件工程课程 |

当前本机的四份随机密码以 DPAPI 密文保存在同一私有目录的 `accounts.json`。不要将管理员/教师密码与学生访问说明一起分发。此功能仍是种子演示账号，不替代 A 后续实现的正式用户注册、账号管理和登录风控。

## 启动与停止

需要更换运行实例时先执行统一停止脚本，再在仓库根目录启动：

```powershell
.\scripts\stop-rag-demo.ps1
.\scripts\start-rag-demo.ps1 -Background -PublicAccess
.\scripts\start-rag-public.ps1
```

已构建时可给后端启动命令增加 `-SkipBuild`。后端只监听 `127.0.0.1:18080`；公网入口由 Cloudflare Quick Tunnel 提供 HTTPS，不开放本机数据库或文件目录。隧道工具位于 `%LOCALAPPDATA%/CampusAI/tools/cloudflared.exe`，应仅使用 Cloudflare 官方发行版并验证官方 SHA-256。

停止演示时执行 `scripts/stop-rag-demo.ps1`，同时关闭隧道、看护进程和后端。启动器检查已有进程和端口，确认网页就绪才保存实际后端进程；重复启动会拒绝，保留已有实例及记录。公网启动前会确认四个账号均拒绝开发密码、只接受各自独立密码，再绑定后端进程编号与启动时间。看护进程在对应后端退出时关闭本次隧道，避免旧入口继续指向后来启动的开发实例。

启动后 `backend/target/rag-public-access.txt` 提供当前网址和软件工程学生登录信息，不含模型密钥；该目录被 Git 忽略，访问说明只在组内分享。问答会消耗所配置 DeepSeek 账号的额度。临时地址依赖本机、后端和隧道进程持续运行，关机、休眠或隧道重启可能导致地址失效；它不提供长期域名、稳定性或服务等级承诺。

正式部署应由负责人控制服务器环境中的模型密钥，用固定域名和 HTTPS 接入现有认证体系，并配置适合团队的调用限额；需要给组员的是业务接口地址，不是模型密钥。公开仓库中不保存当前临时网址、登录密码、模型密钥或个人机器配置。
