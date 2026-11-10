# build-online-exam-platform 交接

本文件记录代码现状和接续路径，不代表未勾选任务已通过验收。当前实施 change 是 `build-online-exam-platform`（spec-driven），工作分支为 `codex/build-online-exam-platform-5-3`。旧五项 change 仅供历史参考。提交前的基线 HEAD 为 `85cb2c0`；接手时以实际 `git log`、`git status` 和 OpenSpec 输出为准。

## 阅读顺序与目录

1. `openspec/changes/build-online-exam-platform/` 的 proposal、design、tasks 和 specs；运行 `openspec status --change build-online-exam-platform --json`、`openspec instructions apply --change build-online-exam-platform --json`。当前任务清单为 **19/59**。
2. `README.md`、`deploy/README.md`：架构、启动、隔离测试。`docs/changes/build-online-exam-platform/` 的 `catalog-api.md`、`openapi.yaml`、`data-dictionary.md`、`scenarios.md`、`verification.md`：契约和证据。
3. `backend/src/main/java/com/onlineexam/platform/`：controller、service、repository、security；`backend/src/main/resources/db/migration/`：V1–V10；`backend/src/test/java/com/onlineexam/platform/`：真实数据库接口测试。
4. `web/admin/`、`web/student/`、`web/shared/` 和 `wechat/student/`：两个 Vue 入口与原生微信小程序；仓库根目录的 `tests/`、`scripts/test.mjs` 保留原 Node 考试演示回归。

## 已完成与本分支实现

- `tasks.md` 已勾选 **5.3、6.3**。5.3 的年级、发布状态、卷类型、开放区间和开始拒绝由可控时钟及 PostgreSQL 接口测试验证。6.3 的任务发布成员与卷版本冻结、跨 Web 会话和微信令牌并发开始、重复进入恢复由 V8/V9、`AttemptService`、数据库部分唯一索引及真实认证路径测试验证。微信身份提供器在测试中是替身，不能据此宣称真机通过。
- **7.1 后端子范围**：普通固定卷/时段卷开始，任务卷开始与恢复，本人读取，题目、分值、版本不可变快照，`serverNow`/`deadlineAt` 和不含答案解析的安全 DTO。普通卷允许不同新键再次开始；任务卷每人每卷至多一份。主要文件为 `StudentAttemptsController.java`、`AttemptService.java`、`AttemptRepository.java`、V8/V9。
- **7.2 已实现子范围**：V10 `attempt_answers`；`PUT /api/v1/me/attempts/{id}/answers` 按快照校验五题型并全量替换，遗漏题目与 `answer:null` 均清空；非法题目、重复题目、选项或超限内容整批拒绝。持有答卷行锁后采样服务端可注入时钟，版本不符返回 `VERSION_CONFLICT`，截止或不可编辑返回 `EXAM_CLOSED`；拒绝请求不写入或递增版本。本人读取返回最新答案。Web `AttemptPage.vue` 与小程序 `pages/attempt/` 接入自动保存、账号/答卷隔离草稿、未知结果先读取核对、保存期间继续编辑和冲突时保留两端内容。未定义自动合并或强制覆盖策略，因此冲突后停止自动写入。
- 原 Node 考试演示及其测试保持独立；前端演示成绩不是生产权威成绩。接口、数据字典、场景索引和验证记录已随实现更新。

## 未完成与外部条件

- **7.1、7.2 均未勾选**。7.1 尚缺真实客户端跨端恢复、客户端时钟变化后的计时验证、完成后普通卷重练；访问时截止冻结依赖 7.3。7.2 尚缺 Web 登录后的实际答题、断网重连、冲突与账号切换浏览器流程，以及微信体验版真机的相同路径。现有 Chrome 冒烟仅渲染匿名登录页。7.2 的服务端与小程序运行时替身测试不能替代这些客户端证据。
- 7.3 手动提交、到期调度、访问时补冻结和计分尚未实现；7.4/7.5 仅有最小答卷编辑与保存入口，题号导航、标记、倒计时、提交确认和体验版验收仍未完成。6.4 的任务逐卷完成状态也依赖后续提交/评分。
- 微信体验版验收需要实际 AppID、已登记的 HTTPS 合法域名、体验成员及真机；这些条件只阻塞对应客户端验收。浏览器控制插件在本机曾因内核资源路径错误无法启动；Chrome 无头页面冒烟可用，完整交互需另建可复跑的浏览器流程。
- 其他未勾选项以 `tasks.md` 为准；不要从“文件存在”推断已验收，不要改写 V1–V10 历史迁移。

## 运行环境和最近证据

项目固定 Node **22.22.2**；最近隔离测试使用 Java **21.0.10**、Maven **3.9.12**、PostgreSQL **17.11**、Spring Boot **3.5.16**。最近本机 Docker Desktop CLI **29.9.3**、Compose **5.7.0**。这些是已记录的本机版本，其他机器以实际环境复核，不依赖个人临时工具目录或代理设置。

从仓库根目录按 `deploy/README.md` 准备忽略的 `deploy/.env` 后可启动开发 Compose。验证命令：

```sh
node --test tests/*.test.cjs
node scripts/test.mjs
npm --prefix web ci
npm --prefix web run build
openspec validate build-online-exam-platform --strict
git diff --check
```

**2026-11-11 实测，针对本交接前的最终功能代码**：`node scripts/test.mjs` 的固定 Node 4/4、Java 15/15 通过，独立 PostgreSQL 空库 Flyway V1–V10 通过，脚本清理自身随机项目和卷；含新增草稿测试的 `node --test tests/*.test.cjs` 为 5/5；Web 两入口构建通过；严格 OpenSpec 校验和 `git diff --check` 通过。历史浏览器、微信替身和环境恢复证据见 `verification.md`，不能算本次重新实测。交接文档与 README 入口的后续编辑仅涉及文档。

## 建议接续

1. 先在隔离环境补 7.2 的真实 Web 端到端用例：登录、开始、编辑、服务器确认后刷新、断网时未同步、未知结果重试、两个会话版本冲突、保存中继续编辑和切换账号。核对数据库答案与页面状态。微信端待真实验收条件具备后验证同一流程；满足 ET02-N/F/B 全部证据后才勾选 7.2。
2. 可独立推进 7.3 的服务端提交事务、到期调度及访问时补处理。先读 exam-taking ET03、design 的状态/时钟/幂等约束，复用当前答卷、快照和答案表；用新增迁移承载新数据。以真实 PostgreSQL、可控时钟、独立并发请求验证提交与截止竞争、重试、宕机恢复及不补写；再接客户端状态。
3. 随提交/冻结闭环复核 7.1 的 ET01-N/B、PA02-N，补真实跨端、时钟变化与重练证据。保持所有未完整验收项未勾选。

## 可直接交给下一位 agent 的提示词

> 请接手 `D:\Online_Exam_Platform` 的 `build-online-exam-platform` change，保留 `codex/build-online-exam-platform-5-3` 分支及现有工作。先检查分支、状态和最新提交，读取仓库规则、本交接文档、当前 proposal/design/tasks/specs、`verification.md`，运行 OpenSpec status/apply 并以其 contextFiles 和进度为准。当前已实现任务卷唯一开始及答案全量保存后端，7.1/7.2 仍未勾选。优先补 7.2 的真实 Web 浏览器保存/断网/冲突证据；如微信体验条件仍缺，准确记录阻塞，再选依赖满足的 7.3 服务端子范围。使用 Node 22.22.2、Java 21 与项目隔离 PostgreSQL 流程，运行目标测试、一键回归、严格规格校验和 diff 检查；同步 API、数据字典、场景与验证记录。不得把替身当真机，不改旧迁移，不清理共享资源，也不擅自勾选、合并或归档。
