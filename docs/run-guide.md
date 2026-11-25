# CampusAI 基础仓库运行与验证说明

本版本提供第一周的三端基础工程。学生端、教师与管理员后台共用一个 Spring Boot 后端，已接入账号登录、当前用户、课程列表、课程详情和退出登录。课程范围由后端按角色及选课关系控制。

为便于小组先完成单机联调，本版默认使用 H2 内存样例库、四个开发账号和 30 分钟有效的内存会话。后端重启后需要重新登录，内存库中的资料也会丢失。E 模块的资料解析、检索与引用问答接口现已实现，运行和持久演示入口见 [RAG 模块说明](rag/README.md)；正式 MySQL 环境和其他业务仍按团队计划集成。

## 1 仓库结构与复用范围

| 目录 | 内容 |
| --- | --- |
| `backend/` | Spring Boot 3.5.4、Spring Security、JDBC、样例数据库、角色与课程权限 |
| `admin-web/` | Vue 3、Vue Router、Vite，教师和管理员登录、课程与账号页面 |
| `student-miniapp/` | uni-app Vue 3，微信小程序与 H5 入口、登录及课程页面 |
| `scripts/verify-api.ps1` | 真实 HTTP 接口冒烟验证 |
| `docs/run-guide.md` | 本运行说明及第一周剩余任务 |
| `docs/six-week-development-plan.md` | 已有六周人员计划 |

三个上游采用选定模块和接口适配的方式集成到本仓库，后续修改在对应目录中协作。每个模块保留来源与 MIT 许可说明。

| 上游仓库 | 当前复用及适配范围 | 来源记录 |
| --- | --- | --- |
| [RuoYi-Vue Spring Boot 3](https://gitee.com/y_project/RuoYi-Vue/tree/springboot3) | 登录、用户信息、菜单与统一响应约定，连接 CampusAI 账号和课程权限 | `backend/THIRD-PARTY-NOTICES.md` |
| [RuoYi-Vue3](https://github.com/yangzongzhuan/RuoYi-Vue3) | 登录、用户信息和退出 API 封装，连接 CampusAI 路由与后台页面 | `admin-web/THIRD-PARTY-NOTICES.md` |
| [RuoYi-App](https://github.com/yangzongzhuan/RuoYi-App) | 登录 API、令牌存取和 uni.request 封装，增加 CLI 构建及课程页面 | `student-miniapp/THIRD-PARTY-NOTICES.md` |

## 2 环境准备

| 工具 | 版本或要求 |
| --- | --- |
| JDK | 17 或 21，设置 `JAVA_HOME` 并加入 PATH |
| Maven | 3.9.x |
| Node.js | 22 LTS，至少 22.12.0 |
| npm | 随 Node.js 安装 |
| PowerShell | 7，执行接口验证脚本使用 |
| 微信开发者工具 | 小程序调试使用，需要团队可用的 AppID 或测试账号 |

首次安装需要能访问 Maven Central 和 npm registry。Windows 下若 `npm.ps1` 被执行策略拦截，可使用下文的 `npm.cmd`。macOS、Linux 使用 `npm` 执行同名命令。

以下启动步骤分别在三个终端执行。每个终端先进入仓库根目录。

```powershell
Set-Location 'E:\School Study\Software_Engineering\期末大作业\CampusAI'
java -version
mvn -version
node --version
npm.cmd --version
```

## 3 启动后端

在第一个终端执行。

```powershell
Set-Location backend
mvn spring-boot:run
```

后端默认地址为 `http://127.0.0.1:8080`。首次启动自动执行 `src/main/resources/schema.sql` 和 `data.sql`，无需单独安装数据库。

开发账号统一密码为 `CampusAI-Dev-2026`。在启动后端前设置 `CAMPUSAI_DEV_PASSWORD` 可修改开发密码，验证脚本使用相同值。

| 账号 | 角色 | 可见课程 | 课程数据库 ID |
| --- | --- | --- | --- |
| `admin` | 管理员 | `CS101`、`SE101` | 1、2 |
| `teacher` | 教师 | `CS101`、`SE101`，均为该教师负责 | 1、2 |
| `student` | 学生 | `CS101` | 1 |
| `student2` | 学生 | `SE101` | 2 |

当前账号由 `DemoAccounts` 初始化，密码使用 BCrypt 编码。账号增删、注册与用户资料维护继续在第一周完善。

如需更改端口，在启动后端的终端中设置环境变量，并同步修改两个前端的代理目标或小程序服务地址。

```powershell
$env:CAMPUSAI_PORT = '8081'
$env:CAMPUSAI_DEV_PASSWORD = 'your-local-demo-password'
mvn spring-boot:run
```

后端读取系统环境变量。前端 `.env.local` 中的配置由 Vite 加载。真实凭证应保存在本地环境变量或被忽略的配置中。

## 4 启动教师与管理员后台

在第二个终端从仓库根目录执行。

```powershell
Set-Location admin-web
npm.cmd ci
npm.cmd run dev
```

打开终端显示的地址，默认 `http://127.0.0.1:5173`。后台使用 `/api` 开发代理访问后端，请求会被转为例如 `http://127.0.0.1:8080/login`。

登录后默认进入工作台。侧栏按角色显示菜单，账号在左下角。开发密码默认为 `CampusAI-Dev-2026`。

| 操作 | 预期结果 |
| --- | --- |
| 使用 `admin` 登录 | 左下角为系统管理员，侧栏包含「用户与角色」 |
| 打开「课程与章节」 | 课程名称和编号来自课程接口；章节与发布状态显示待对接 |
| 打开资料、题库、问答、统计 | 显示原型示例数据，页脚注明尚未写入后端 |
| 直接访问 `#/users` | 管理员可以打开；教师回到工作台并提示没有权限 |
| 刷新页面 | 通过当前令牌重新读取用户信息并恢复登录 |
| 退出后使用 `teacher` 登录 | 显示教师身份，侧栏没有「用户与角色」 |
| 使用 `student` 登录后台 | 显示教师及管理员权限提示 |
| 输入错误密码 | 显示错误信息，保留在登录页 |
| 退出后访问 `#/overview` | 返回登录页 |

本周已接通的接口是 `POST /login`、`GET /getInfo`、`GET /getRouters`、`GET /campusai/courses` 和 `GET /campusai/courses/{id}`。资料、题库、问答、统计和用户表在 `src/api/content.js` 返回示例，对应路径写在该文件里，等 A、B 的接口就绪后替换。

可以复制 `admin-web/.env.example` 为 `.env.local`，或在该终端设置代理目标后重启 Vite。

```powershell
$env:CAMPUSAI_BACKEND_URL = 'http://127.0.0.1:8081'
npm.cmd run dev
```

生产构建检查与本机产物预览如下。预览地址以终端输出为准，默认端口为 4173。

```powershell
npm.cmd run build
npm.cmd run preview
```

正式部署时，由 Web 服务器配置 `/api` 反向代理到后端。Vite 开发及预览命令提供本地验证入口。

## 5 启动学生端

### 5.1 微信小程序

在第三个终端从仓库根目录执行。

```powershell
Set-Location student-miniapp
npm.cmd ci
```

团队 AppID 保存在本机的 `student-miniapp/src/project.config.json`，该文件已加入 Git 忽略规则。首次配置时可使用已有开发产物中的配置作为模板。如果没有产物，先生成一份模板。

```powershell
npm.cmd run build:mp-weixin
Copy-Item -LiteralPath 'dist/build/mp-weixin/project.config.json' -Destination 'src/project.config.json'
```

在 `src/project.config.json` 中将 `appid` 改为团队可用的 AppID，然后启动下面的命令。编译器会将本机配置带入产物。只在 `dist` 中修改 `appid` 的话，重新构建可能将它写回 `touristappid`。

```powershell
npm.cmd run dev:mp-weixin
```

使用微信开发者工具导入 `student-miniapp/dist/dev/mp-weixin`。如果后端使用其他端口，设置 `VITE_CAMPUSAI_API_BASE_URL` 后重启构建。

学生端使用 `package-lock.json` 锁定依赖。请使用符合 `package.json` 要求的 Node.js 版本。

开发者工具本机调试可在本地设置中关闭合法域名校验，以连接默认地址 `http://127.0.0.1:8080`。真实设备的 `127.0.0.1` 指设备自身，真机需要配置可达的后端地址及平台允许的请求域名。

在启动小程序构建的终端配置实际服务地址。局域网联调时，后端需设置 `CAMPUSAI_ADDRESS=0.0.0.0` 后重新启动，并确认测试网络可访问该端口。

```powershell
$env:VITE_CAMPUSAI_API_BASE_URL = 'http://192.168.1.10:8080'
npm.cmd run dev:mp-weixin
```

| 操作 | 预期结果 |
| --- | --- |
| 使用 `student` 登录 | 我的课程只显示 `CS101` |
| 点击该课程 | 打开课程详情，显示课程编号及简介 |
| 退出并使用 `student2` 登录 | 我的课程只显示 `SE101` |
| 修改详情页路由访问未选课程 ID | 接口拒绝返回该课程信息 |
| 后端停止后重新加载 | 显示连接错误并可重试 |
| 会话失效后请求课程 | 清除失效登录态并回到登录页 |
| 课程详情 → 课程问答 → 点击示例问题 | 显示聊天式回答，以及资料名和段落 |
| 点击「查看原文」，再点击「收起原文」 | 原文片段可展开和收起 |

### 5.2 H5 浏览器联调入口

同一份学生端代码提供 H5 开发入口，便于在浏览器中检查登录和课程交互。

```powershell
npm.cmd run dev:h5
```

打开 `http://127.0.0.1:5174`。H5 默认使用 `/api` 代理，通过 `CAMPUSAI_BACKEND_URL` 修改目标。使用默认 H5 代理时，先清除仅供小程序使用的绝对地址变量。

```powershell
Remove-Item Env:VITE_CAMPUSAI_API_BASE_URL -ErrorAction SilentlyContinue
$env:CAMPUSAI_BACKEND_URL = 'http://127.0.0.1:8080'
npm.cmd run dev:h5
```

发布构建命令如下，产物分别位于 `dist/build/mp-weixin` 和 `dist/build/h5`。

```powershell
npm.cmd run build:mp-weixin
npm.cmd run build:h5
```

H5 静态部署同样需要 `/api` 代理。

### 5.3 学生端第一周验收记录

2026 年 11 月 25 日在 `feat/student-miniapp` 分支验收。微信开发者工具使用已导入的 `dist/dev/mp-weixin` 项目，H5 使用 `http://127.0.0.1:5176`，两端连接 `http://127.0.0.1:8080` 的 Spring Boot 后端和 H2 内存样例库。

| 检查项 | 实际结果 |
| --- | --- |
| 依赖与发布构建 | 隔离目录中 `npm.cmd ci` 和 `npm.cmd ls --depth=0` 均通过，`@dcloudio/types` 与锁文件均为 `3.4.31`。微信和 H5 发布构建均成功 |
| 错误密码 | 微信模拟器和 H5 均提示“账号或密码错误” |
| 学生登录与课程详情 | 两端均验证 `student` 只看到 `CS101`，可查看计算机导论详情 |
| 退出后切换学生 | 两端均返回登录页，`student2` 登录后只看到 `SE101`，可查看软件工程详情 |
| 问答原型 | 微信模拟器及 H5 均验证从课程详情进入、发送示例问题及展开原文。H5 另验证收起原文和非示例问题提示 |
| 真实 HTTP 接口 | `scripts/verify-api.ps1` 通过。学生课程范围、跨课程拒绝和退出后令牌失效均符合预期 |
| 后端回归 | `mvn test` 共 86 项，均通过 |

本次覆盖学生登录、我的课程和课程详情，以及微信模拟器断开后重试连接的操作。微信真机待验。

学生端页面清单为登录、我的课程、课程详情和课程问答。问答页使用 `GET /campusai/courses/{id}` 获取课程信息并校验课程访问权限，回答和引用来自 `docs/rag/samples` 中的示例，展示为「原型预览」。真实检索、问答与来源查询的接口约定见 [RAG 接口文档](rag/api.md)。

## 6 自动验证

### 6.1 后端集成测试

从仓库根目录执行。测试在 Spring 应用内部执行认证、数据库和权限链路，使用隔离的内存样例库。

```powershell
mvn -f backend/pom.xml test
```

构建可执行 JAR 并启动的方式如下。

```powershell
mvn -f backend/pom.xml package
java -jar backend/target/campusai-backend-0.1.0-SNAPSHOT.jar
```

### 6.2 真实 HTTP 接口验证

保持后端运行，在仓库根目录另开终端执行。

```powershell
pwsh -NoProfile -File ./scripts/verify-api.ps1
```

自定义端口时传入 `-BaseUrl`。自定义密码时，在脚本终端设置与后端相同的 `CAMPUSAI_DEV_PASSWORD`，或使用脚本的 `-DemoPassword` 参数。

```powershell
pwsh -NoProfile -File ./scripts/verify-api.ps1 -BaseUrl http://127.0.0.1:8081
```

脚本检查匿名访问、错误密码、四个账号登录与角色、课程列表、课程详情范围、学生后台权限及退出后的令牌失效。每项通过输出 `PASS`，任一失败以非零状态结束。

### 6.3 共同接口约定

| 方法与路径 | 认证 | 返回或行为 |
| --- | --- | --- |
| `GET /captchaImage` | 公开 | `captchaEnabled=false`，供学生端登录页初始化 |
| `POST /login` | 公开 | JSON `username`、`password`，响应顶层 `token` |
| `GET /getInfo` | Bearer | 顶层 `user`、`roles`、`permissions` |
| `GET /getRouters` | 教师、管理员 | 顶层 `data` 包含课程菜单 |
| `GET /campusai/courses` | Bearer | `data` 为可访问课程数组 |
| `GET /campusai/courses/{id}` | Bearer | `data` 为可访问课程详情 |
| `POST /logout` | Bearer | 撤销当前会话 |

受保护请求使用 `Authorization: Bearer <token>`。客户端同时检查 HTTP 状态及若依风格的 JSON `code`。成功业务码为 200，错误信息在 `msg` 中。课程字段统一为 `id`、`courseCode`、`name`、`description`，详情使用数据库 `id`。

## 7 本次验证记录

后端执行 `mvn -o -f backend/pom.xml package` 成功，4 项 Spring 集成测试全部通过，生成可执行 JAR。测试覆盖学生与教师课程隔离、管理员可见范围、课程详情权限、错误密码、非法请求与退出后的令牌撤销。

管理后台执行 `npm run build` 成功。两端页面模板、认证封装和课程交互通过离线检查，覆盖错误密码、角色限制、课程列表与详情、会话恢复或失效、退出登录。

基础工程初始化时，执行环境限制了回环连接、npm 外网访问和浏览器启动。11 月 25 日完成的真实 HTTP、微信和 H5 验收见第 5.3 节。

## 8 第一周仍需完善的功能与交付

| 负责人 | 剩余工作 | 验收产物 |
| --- | --- | --- |
| A 思佳怡 | 统一账号创建规则、注册方案和角色权限矩阵，将演示身份迁移为持久化用户；定稿错误码及接口规范 | 权限矩阵、认证接口文档、正式账户表方案 |
| B 陈用礼 | 定稿课程、选课、章节、资料及练习领域接口，明确教师课程归属、学生加入方式和业务状态 | 业务 API 初稿、用例图、功能模块图 |
| C 张涵 | 第一周学生端本机验收已完成 | 小程序编译及登录记录、页面清单和问答原型见第 5.3 节 |
| D 卢冰玲 | 第 2 周把资料上传、解析状态、失败原因和发布接到真实接口；本周页面框架与角色菜单已可演示 | 可操作的上传和发布页面 |
| E 杨宇轩 | 使用真实样例验证文本型 PDF、DOCX、TXT 提取及来源位置，试跑中文 BM25，验证模型可用性和失败响应 | 三类解析结果、片段字段、检索及模型验证记录 |
| F 洪少敏 | 与 A、B 定稿 ER 图、MySQL 8 开发环境及迁移脚本，准备样例资料、共享测试环境及测试清单 | ER 图、数据库初始化说明、数据来源与验收清单 |
| 全组 | 各成员在自己的环境运行模块，完成独立分支提交、同伴评审和 M1 验收 | 启动记录、合并请求、第一周验收记录 |

资料上传与发布、收藏、BM25 课程内检索、带引用问答、练习与统计按后续周计划开发。第一周的 M1 还需要完成三种资料格式与模型调用的可行性验证，以及全员环境和协作验收。完整分工见[六周开发计划](six-week-development-plan.md)。
