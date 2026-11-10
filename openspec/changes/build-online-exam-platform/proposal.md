# Proposal

## Why

当前项目仅有浏览器本地考试演示，题目、计分和进度由前端处理。需要将已确认的 xzs 业务范围落实为管理端、学生 Web 端、微信小程序和共用 Java 后端的可交付系统。

## What Changes

- 建立 Java、Spring Boot、Spring Security、MyBatis、PostgreSQL 分层单体后端与独立 Vue 管理端、学生端及原生微信小程序。
- 覆盖账号、学科、五类题目、三类试卷、多试卷年级任务、作答、批改、成绩、错题、资料、消息、操作日志和统计。
- 按学生与任务试卷限制一次作答，任务包含多张试卷。单选、多选、判断自动判分，填空、简答由本人自行批改。
- 服务端持久化草稿并控制截止时间，截止冻结已保存答案，提交幂等，历史答卷绑定不可变版本。
- 提供功能到 capability、页面、API、验收场景的对照，以及接口契约、数据模型、部署和测试计划。
- **BREAKING** 规划基线采用 Java 后端，替代已有 Fastify 方案及“每个任务一份答卷”的约束。本 change 统一承接已有五项规划覆盖的本次交付范围，补齐生产前端和小程序。

## Capabilities

### New Capabilities

- `identity-access` — 注册、登录、账户管理、角色资源授权、微信绑定。
- `question-bank` — 学科、五类题目、富文本和附件管理。
- `paper-authoring` — 三类试卷、组卷、发布、版本与可见性。
- `task-assignment` — 年级任务、多试卷分配及一次性完成。
- `exam-taking` — 服务端答卷、保存、恢复、计时与交卷。
- `exam-grading` — 自动判分、自行批改与最终成绩。
- `learning-records` — 历史答卷、错题及个人资料。
- `messaging-activity` — 消息已读、操作动态、日志与统计。
- `client-experience` — 三端页面、导航、题目呈现与交互状态。
- `platform-delivery` — 接口约定、构建部署、验证、监控和恢复。

### Modified Capabilities

当前 `openspec list --specs` 返回空列表，全部作为新增主规格候选。`identity-access` 与旧 change 同名能力由本提案承接，后续归档只采纳本次已确认的定义。

## Impact

计划交付 `backend/`、`web/admin/`、`web/student/`、`web/shared/`、`wechat/student/`、`deploy/`、`docs/` 和测试脚本。已有演示的本地存档保留为演示数据，其倒计时与计分用例用于迁移验收，生产成绩由后端生成。

参考基线为 mindskip/xzs 的 `0664766be421e01b723585fc93fd7dc9d0225e18`，POM 标注 3.9.0，查阅日期为 2026-11-10。源码证据、功能对照、已确认决定和补充设计详见 design.md。

用户已确认本 change 统一承接完整范围。旧 `design-exam-product`、`build-exam-backend`、`secure-exam-platform`、`verify-exam-platform`、`operate-exam-platform` 作为历史规划参考，本项目实施和验收使用本 change。旧 change 的未执行任务不计作已完成，其目录的整理或归档另行处理。
