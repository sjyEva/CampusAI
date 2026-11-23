# 校园 AI 课程知识助手 | Campus AI Course Assistant

[中文](#中文说明) | [English](#english)

## 中文说明

### 项目简介

校园 AI 课程知识助手是一个面向本校大学生的课程学习辅助系统，也是软件工程课程期末项目。项目计划整合课程资料管理、智能检索与 AI 问答，帮助学生更方便地查找课程知识、理解学习内容，并为管理员提供课程与资料维护能力。

### 项目状态

项目处于第一周基础工程阶段，已建立后端、管理后台和学生小程序工程。基础链路包括登录、角色验证、课程列表、课程详情和退出登录。管理后台已通过构建，学生端平台构建与真实浏览器联调受当前执行环境限制，验证记录见运行说明。完整功能以六周计划为准。

E 的解析、BM25 检索和引用问答模块现已提供真实后端接口、独立演示页及固定开发评估。支持文本型 PDF、DOCX、TXT，按课程和资料版本隔离来源；增加技术标识符核对、逐条引文位置和旧版本操作保护，未配置模型时返回检索原文。[模块运行与交接说明](docs/rag/README.md)、[接口约定](docs/rag/api.md)、[评估与改进对照](docs/rag/evaluation/README.md)。正式两端接入、问答历史和真实模型验证仍需按团队计划完成。

### 计划功能

| 模块 | 计划内容 |
| --- | --- |
| 用户与权限 | 用户登录、个人信息管理、用户与管理员权限区分 |
| 课程与资料 | 按课程组织学习资料，支持资料浏览与管理 |
| 智能检索 | 根据关键词或自然语言问题查找相关课程内容 |
| AI 问答 | 结合课程资料生成回答，并展示可追溯的参考来源 |
| 管理员端 | 管理用户、课程、资料及知识库内容 |

AI 回答用于辅助学习，使用时应结合原始课程资料核对。

### 技术方案

基础工程采用 Spring Boot 3、Vue 3 和 uni-app，按模块复用 RuoYi-Vue Spring Boot 3、RuoYi-Vue3 和 RuoYi-App 的认证及接口封装。各模块保留上游来源与 MIT 许可。开发演示默认使用 H2 内存样例数据库和临时会话。持久化存储与 AI 模型、检索方案见六周计划。

### 仓库目录

- `backend/` — 后端接口、角色校验和课程数据。
- `admin-web/` — 教师及管理员 Web 后台。
- `student-miniapp/` — 学生微信小程序与 H5 联调入口。
- `scripts/` — 联调验证脚本。
- `docs/` — 运行说明及项目文档。

### 本地运行

参阅[运行与验证说明](docs/run-guide.md)，其中包含三端启动、演示账号、联调步骤及第一周待办。[六周开发计划](docs/six-week-development-plan.md)提供完整分工。

真实 API Key、数据库密码等敏感配置应保存在本地环境变量或被 Git 忽略的配置文件中，不得提交到仓库。

### 协作约定

- `main` 保存经过检查、能够运行的代码；初始化阶段先完善项目骨架。
- 每项任务建立独立分支，例如 `feat/login`、`feat/course-search`、`fix/upload-error`。
- 通过 Pull Request（或平台对应的合并请求）提交变更，由其他组员检查后合并。
- 任务应明确负责人、范围、截止时间和验收条件。
- 修改接口或数据库结构时，同步更新对应文档，并通知相关模块负责人。
- 提交前检查相关功能，不提交依赖目录、缓存、真实密钥及无关大文件。

提交说明示例：

```text
feat: 实现课程列表接口
fix: 修复资料上传失败
docs: 补充本地运行说明
```

### 首阶段目标

- [ ] 明确核心需求与模块分工。
- [ ] 确定技术栈与统一开发环境。
- [ ] 建立前后端基础工程并验证接口连通。
- [ ] 完善安装、配置和启动说明。
- [ ] 完成一次全员分支提交与合并演练。

### 课程交付物

除源代码外，项目计划整理需求分析、系统设计、接口文档、测试记录、部署说明及项目演示材料。具体内容以课程要求为准。

---

## English

### Overview

Campus AI Course Assistant is a course learning support system for students at our university, developed as a software engineering course final project. It aims to combine course material management, intelligent retrieval, and AI-assisted question answering, with a student interface and an administration interface.

### Project Status

The week-one scaffold contains a backend, an administration web app, and a student mini-program with an H5 development entry. It connects sign-in, roles, course listing, course details, and sign-out. The admin production build passed. Student platform builds and live browser integration require an environment with the required network and runtime access. See the setup guide for verification records.

The parsing/BM25/citation-RAG module now exposes real protected APIs and a standalone demo at `/rag-demo.html`. It supports text PDF, DOCX and UTF-8 TXT, authoritative course/publication/version filtering, and explicit retrieval fallback when no model is configured. See [module documentation](docs/rag/README.md) and [developer-authored evaluation](docs/rag/evaluation/README.md). Full client integration, history and real-provider validation remain team follow-up work.

### Planned Features

| Module | Planned capabilities |
| --- | --- |
| Users and permissions | Sign-in, profile management, and separate user and administrator permissions |
| Courses and materials | Organize, browse, and manage learning materials by course |
| Intelligent retrieval | Find relevant course content using keywords or natural language queries |
| AI question answering | Generate answers using course materials and show traceable references |
| Administration | Manage users, courses, materials, and knowledge base content |

AI-generated answers support learning and should be checked against the original course materials.

### Technology

The scaffold uses Spring Boot 3, Vue 3, and uni-app. Selected authentication modules and response contracts are adapted from RuoYi-Vue Spring Boot 3, RuoYi-Vue3, and RuoYi-App, with source attribution and MIT notices in each module. An in-memory H2 sample database and temporary sessions support local integration.

### Repository Structure

- `backend/` — API, role checks, and course data.
- `admin-web/` — Teacher and administrator web app.
- `student-miniapp/` — Student WeChat mini-program.
- `scripts/` — Integration checks.
- `docs/` — Setup and project documentation.

### Local Setup

See the [setup and verification guide](docs/run-guide.md) for startup commands, demo accounts, verification steps, and the remaining week-one tasks.

Keep real API keys, database passwords, and other secrets in local environment variables or Git-ignored configuration files. Never commit them.

### Collaboration

- Keep reviewed, runnable code on `main`; establish the project skeleton during initialization.
- Create a separate branch for each task, such as `feat/login`, `feat/course-search`, or `fix/upload-error`.
- Submit changes through a pull request or the hosting platform's equivalent merge request, and obtain review from another team member before merging.
- Define an owner, scope, deadline, and acceptance criteria for each task.
- Update documentation and notify the relevant module owners when changing APIs or database schemas.
- Check affected functionality before submitting. Exclude dependency directories, caches, real secrets, and unrelated large files.

Example commit messages:

```text
feat: add course list API
fix: resolve material upload failure
docs: add local setup instructions
```

### Initial Milestones

- [ ] Agree on core requirements and module ownership.
- [ ] Confirm the technology stack and shared development environment.
- [ ] Create the frontend and backend skeletons and verify API connectivity.
- [ ] Document installation, configuration, and startup steps.
- [ ] Have every team member complete a branch and merge exercise.

### Course Deliverables

In addition to source code, planned deliverables include requirements analysis, system design, API documentation, test records, deployment instructions, and presentation materials. Final deliverables will follow the course requirements.

