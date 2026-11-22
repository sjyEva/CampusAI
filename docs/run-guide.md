# CampusAI 基础仓库运行与验证说明

本版本提供第一周的三端基础工程。学生端、教师与管理员后台共用一个 Spring Boot 后端，已接入账号登录、当前用户、课程列表、课程详情和退出登录。课程范围由后端按角色及选课关系控制。

为便于小组先完成单机联调，本版默认使用 H2 内存样例库、四个开发账号和 30 分钟有效的内存会话。后端重启后需要重新登录，课程数据重新初始化。MySQL 持久化、资料处理、检索与 AI 问答列在第一周及后续开发任务中。

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

| 操作 | 预期结果 |
| --- | --- |
| 使用 `admin` 登录 | 显示管理员身份、全部课程和管理账号入口 |
| 打开课程卡片 | 显示真实接口返回的课程编号与简介 |
| 刷新页面 | 通过当前令牌重新读取用户信息并恢复登录 |
| 退出后使用 `teacher` 登录 | 显示教师身份、负责课程和教学账号入口 |
| 使用 `student` 登录后台 | 显示教师及管理员权限提示 |
| 输入错误密码 | 显示错误信息，保留在登录页 |
| 退出登录后访问课程地址 | 返回登录页 |

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
npm.cmd install
npm.cmd run dev:mp-weixin
```

将 `student-miniapp/src/manifest.json` 中 `mp-weixin.appid` 填为团队可用的小程序 AppID。使用微信开发者工具导入构建生成的 `student-miniapp/dist/dev/mp-weixin` 目录。

学生端依赖版本按 DCloud 官方 Vite 模板固定。本次环境无法下载 uni-app 依赖，尚未生成 `package-lock.json`。在可联网环境首次成功安装并验证后，应将实际生成的锁文件提交，后续成员使用 `npm ci`。

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

H5 静态部署同样需要 `/api` 代理。两种学生端构建仍需在具备 uni-app 依赖的环境执行验收。

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

尝试启动后端 JAR 时，当前执行环境在 Tomcat 初始化回环连接处返回 `Permission denied: connect`。因此本次真实 HTTP 冒烟脚本及浏览器到后端的联调未执行成功。当前环境也限制 npm 外网访问和浏览器启动，学生端依赖安装、微信及 H5 完整编译仍待在具备相应环境的机器上执行。上面的启动与验证命令用于完成这些验收。

## 8 第一周仍需完善的功能与交付

| 负责人 | 剩余工作 | 验收产物 |
| --- | --- | --- |
| A 思佳怡 | 统一账号创建规则、注册方案和角色权限矩阵，将演示身份迁移为持久化用户；定稿错误码及接口规范 | 权限矩阵、认证接口文档、正式账户表方案 |
| B 陈用礼 | 定稿课程、选课、章节、资料及练习领域接口，明确教师课程归属、学生加入方式和业务状态 | 业务 API 初稿、用例图、功能模块图 |
| C 张涵 | 在可联网环境完成 uni-app 安装与锁文件，使用团队 AppID 完成微信工具验收；补充问答入口与引用展示原型 | 小程序编译及登录记录、页面清单、问答原型 |
| D 卢冰玲 | 完成真实浏览器联调，设计用户、资料管理和统计页面，核对教师与管理员后续菜单权限 | 后台运行记录、管理端原型与菜单清单 |
| E 杨宇轩 | 使用真实样例验证文本型 PDF、DOCX、TXT 提取及来源位置，试跑中文 BM25，验证模型可用性和失败响应 | 三类解析结果、片段字段、检索及模型验证记录 |
| F 洪少敏 | 与 A、B 定稿 ER 图、MySQL 8 开发环境及迁移脚本，准备样例资料、共享测试环境及测试清单 | ER 图、数据库初始化说明、数据来源与验收清单 |
| 全组 | 各成员在自己的环境运行模块，完成独立分支提交、同伴评审和 M1 验收 | 启动记录、合并请求、第一周验收记录 |

资料上传与发布、收藏、BM25 课程内检索、带引用问答、练习与统计按后续周计划开发。第一周的 M1 还需要完成三种资料格式与模型调用的可行性验证，以及全员环境和协作验收。完整分工见[六周开发计划](six-week-development-plan.md)。
