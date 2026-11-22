# 校园 AI 课程知识助手 | Campus AI Course Assistant

[中文](#中文说明) | [English](#english)

## 中文说明

### 项目简介

校园 AI 课程知识助手是一个面向本校大学生的课程学习辅助系统，也是软件工程课程期末项目。项目计划整合课程资料管理、智能检索与 AI 问答，帮助学生更方便地查找课程知识、理解学习内容，并为管理员提供课程与资料维护能力。

### 项目状态

项目处于初始化与需求设计阶段。以下内容为计划范围，不代表功能已经实现；具体需求、技术栈和运行步骤将在开发过程中逐步补充。

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

前端、后端、数据库、AI 模型接口与检索方案尚待团队确认。确定后将在此补充版本要求、环境配置与选型说明。

### 规划目录

以下为拟采用的仓库结构，相关目录将随开发创建：

- `frontend/`：用户端与管理员端前端代码。
- `backend/`：业务接口、权限控制与 AI / 检索服务。
- `docs/`：需求、系统设计、接口约定、测试及部署文档。
- `.env.example`：环境变量示例，仅包含配置项和占位值。
- `README.md`：项目介绍与开发入口（本文件）。

### 本地运行

当前尚未提供可运行的应用。基础工程搭建后，此处将补充：

1. 开发环境与依赖版本。
2. 依赖安装步骤。
3. 数据库初始化与示例数据导入步骤。
4. 环境变量配置说明。
5. 前后端启动命令与访问地址。

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

The project is in the initialization and requirements design stage. The items below describe the planned scope, rather than completed features. Requirements, technology choices, and setup instructions will be refined during development.

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

The frontend, backend, database, AI model API, and retrieval approach are yet to be confirmed by the team. Version requirements, configuration details, and design decisions will be added once agreed.

### Planned Repository Structure

The following directories will be created as development progresses:

- `frontend/`: Frontend code for the student and administration interfaces.
- `backend/`: Business APIs, access control, and AI / retrieval services.
- `docs/`: Requirements, system design, API contracts, testing, and deployment documentation.
- `.env.example`: Example environment variables with placeholder values only.
- `README.md`: Project overview and development entry point (this file).

### Local Setup

A runnable application is not yet available. Once the initial application is in place, this section will include:

1. Required development tools and dependency versions.
2. Dependency installation steps.
3. Database initialization and sample data import.
4. Environment variable configuration.
5. Frontend and backend startup commands and local URLs.

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

