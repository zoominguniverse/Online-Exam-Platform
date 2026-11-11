# 当前交付：53/53，完整平台门禁通过

截至香港时间 2026-11-11 16:35:03，build-online-exam-platform全部53项实施任务已按授权勾选，OpenSpec apply为all_done。本轮补齐原复核10项缺口；具体实现及证据见[收尾验收](completion-acceptance.md)、[最新报告](evidence/2026-11-11-completion/README.md)和[发布评审材料](release-review.md)。

完整平台门禁（非仅Web子范围）通过：Node3/3、Java28/28、真实Chromium10/10、双入口构建、73操作契约、30需求/71场景引用、严格规格与diff。45入口三宽度、16空视图、35失败恢复、14写失败/重复抑制及完整键盘答题均有记录。最终镜像回退/恢复通过，同后端既有100会话600秒容量及两项真实代码变异证据由78个后端文件SHA-256一致性复核支持。

当前分支仍为codex/build-online-exam-platform-7-2，参考HEAD c63f6e9；修改未提交。V1–V13不改，后续迁移从V14开始。不提交、推送、合并、归档或生产部署；旧五项规划和主规格整理仍属后续工作。生产HTTPS/外部机密/值班/定时备份配置及既有依赖公告见发布评审材料，不将本地验收说成已上线。

## 以下为此前复核与实施历史

# 当前任务复核：43/53

用户已授权勾选。本次按代码和既有成功证据新增勾选26项；当前apply为ready，仍有10项未完成：4.4、5.4、6.2、9.3、10.1、10.2、10.5、11.1、12.4、12.5。具体缺口和已勾选依据见[任务复核](task-review.md)。上一轮将36项统称“仅待审阅”不准确，现已更正：既有Web门禁通过不能替代缺失的指定场景验收。

本次不修改业务代码，不重复宣称执行全部业务回归；既有Node3/Java28/Chromium9和容量/发布恢复报告仍有效于其明确覆盖范围。后续可以按本次授权，在完成指定验证后更新勾选；提交、推送、合并和归档仍未授权。

## 以下为本次授权前的实施记录

# 当前交接：按 Web 范围 apply

本节覆盖下文旧交接。使用change build-online-exam-platform，现有规划仅含管理端、学生Web和共用后端。先检查git分支/状态与OpenSpec status/apply并读contextFiles。当前基线c63f6e9，分支codex/build-online-exam-platform-7-2，全部修改仍在工作区。apply为ready、17/53，36项保留未勾选以供审阅。

本轮已将旧范围落实到实现：移除专属移动客户端/身份入口/Provider及替身测试，认证仅保留Cookie/CSRF；V1–V13迁移不改，旧表仅保留生命周期及恢复清理。两独立Web会话替代旧跨认证用例。当前接口契约73个操作、30项Requirement/71项Scenario。当前技术门禁不再读取任何外部客户端验收文件；发布门禁仍检查任务审阅状态，不能绕过未勾选约束。

基础回归包括Node3项、Java28项（含旧令牌拒绝/清理、全部Web会话撤销及JSON边界）、Chromium9项（含两个入口版本/断网重试、表单失焦校验），最终结果及容量/RPO/RTO以[当前证据](evidence/2026-11-11-web-scope/README.md)为准。先读[交付矩阵](web-backend-delivery.md)、[当前场景](scenarios.md)与verification顶部，再看下面历史。

使用Node22.22.2、Java21、独立PostgreSQL17.11。若容器JVM的Maven TLS不可用，可运行scripts/gate-host-maven.cjs，仍只通过宿主访问官方源并校验文件，缓存位于忽略的tmp/toolchain，结束关闭临时传输。测试均使用随机自有项目/卷，不清理共享资源。新增迁移从V14开始；不擅自勾选、提交、推送、合并或归档。


最终 Web 门禁于香港时间 2026-11-11 15:54:15 完成：Node **3/3**、Java **28/28**、Chromium **9/9**，零失败/跳过/重试；双入口构建、73个操作契约、30项Requirement/71项Scenario索引、严格规格及diff检查通过。真实后台切换观察到可信隐藏/显示事件，返回后服务端重新取时，日期偏移到2040仍显示19:59。成功桌面/375px截图已打开检查。统一门禁项目 exam-gate-1794383522594、浏览器 exam-browser-412f463751。

100会话容量及100份截止处理、两项真实计分/授权变异、故意版本不匹配的真实JUnit负向控制均有本轮成功记录。最终Web镜像另经 exam-release-test-877c533fc3 重新执行配对发布/回退/恢复（不重复容量），全部通过。只清理各次自己的随机项目/卷。V1–V13与之前快照SHA-256一致；当前apply仍ready、17/53，36项仅待审阅，未勾选、提交、推送、合并或归档。已知依赖公告仍按 dependencies.md 作为发布前评审项，技术门禁通过不代表发布批准。

## 以下为旧范围交接历史（不作为当前任务或门禁输入）

# build-online-exam-platform 交接

## 当前：Web 与共用后端续接交付（2026-11-11）

当前仍为 `codex/build-online-exam-platform-7-2`、基线 `c63f6e9`，全部修改未提交。用户后续要求继续与小程序无关部分；本轮已接入 Web 完整考试/批改、记录错题、资料头像、消息动态、管理查分/审计/统计，以及容器发布、观测告警、备份恢复和容量门禁。详细范围见 [Web/后端交付矩阵](web-backend-delivery.md)，当前最终结果以 [验收证据](evidence/2026-11-11-web-backend/README.md) 和 verification 顶部为准。

- 保留 V1–V11，仅新增 V12 学习/沟通表、V13 回退兼容删除清单触发器。主观批改唯一确认，非法分数整体拒绝、完成后改分409；分数/错题/进度同事务。真实 exam_app 无DDL权限路径另有集成测试。
- Web 已有服务端锚定计时、题号与标记、实际未答确认、结果/SELF、历史图片授权和原请求丢失响应重试。任务完成实时由每卷最终状态投影；归档保留已开始答卷。
- 一键 `scripts/gate.mjs --scope web`：Node/Java、真实浏览器、构建、契约/严格规格/diff，加上独立容量/截止、配对回退恢复、变异报告。`--scope platform` 在40项未勾选/真实微信证据缺失时预检退出1，不能当作意外回归。
- 完整运维验收项目 `exam-release-test-40cd70bf92`：100会话约608秒、成功率100%，列表/保存/交卷p95约5.58/11.47/335.42ms；100份受控到期答卷不经HTTP补处理，最大冻结延迟1.60秒。RPO12.231秒，含启动/登录/图片核对的恢复15.702秒。完整报告会随验收证据保留。
- 固定 Node22.22.2、Java21、PostgreSQL17.11。Docker Desktop 本机安装于 `D:/Docker/`；测试引擎若未运行先启动，但不触碰其他项目资源。脚本只清理自身随机项目/卷，保留tmp报告、备份和专属镜像。
- 微信真机条件仍缺；本轮未延展小程序页面。不要把后端替身或Chromium当体验版，不擅自勾选/提交/推送/合并/归档。OpenSpec仍为ready、19/59。

接续先检查实际git状态及OpenSpec status/apply，读全部contextFiles、verification和本节，再审阅证据与diff。后续新增迁移从V14开始，禁止编辑V1–V13。下文为此前交接历史；其中“尚未实现”描述的是上一阶段，当前实现范围以本节和交付矩阵为准。

## 上一阶段交接原文

本文件记录实现与接续路径，不代表未勾选任务已验收。本轮从干净 `main` 的 `c63f6e9de010493f8c4856189087a4fbf52ef4a6` 接手，新建 `codex/build-online-exam-platform-7-2`，修改尚未提交。此前 `codex/build-online-exam-platform-5-3` 已进入 main。接手时以实际 `git status`、`git log` 和 OpenSpec 输出为准。

## 阅读顺序

1. 仓库规则、README、`deploy/README.md`、本交接及 `verification.md`。
2. 执行 `openspec status --change build-online-exam-platform --json`、`openspec instructions apply --change build-online-exam-platform --json`，读取全部 contextFiles（proposal/design/tasks/10 项 specs）。本轮为 `spec-driven`、apply `ready`，**19/59**。
3. 本目录 `catalog-api.md`、`openapi.yaml`、`data-dictionary.md`、`scenarios.md` 和 [真实 Web 证据](evidence/2026-11-11-web-saving/README.md)。旧五项 change 只作历史参考。
4. `AttemptService`、`AttemptRepository`、`AttemptLifecycle`、`AttemptDeadlineConfiguration`、V11、`IdentityIntegrationTest`、`web/student/src/AttemptPage.vue`、`web/e2e/attempt-saving.spec.ts` 及小程序 `pages/attempt/`。

## 已实现与本轮进展

- 原有 **5.3、6.3 已勾选**：可见性/开始资格、冻结任务成员及卷版本、每学生每任务卷唯一答卷、并发开始与恢复。微信身份适配器测试为替身，不能代表真机。
- **7.1 后端范围**：普通卷/任务卷开始、安全且不可变快照、本人读取和原截止。现新增到期访问补冻结及完成后普通卷独立重练证据；真实 Web/微信跨端、客户端时钟与后台计时仍缺。
- **7.2**：五题型全量保存、版本冲突、账号/答卷隔离草稿。真实 Chromium 六项用例包含五项保存流程：保存刷新、断网重连、双会话冲突、响应丢失后继续编辑、跨标签账号切换。核对实际 PostgreSQL 答案与版本，保留截图及摘要。修复 Vue 克隆失败、空多选、待确认请求核对、冲突重读和旧账号异步响应。小程序同步请求副本/清空语义，只有 Node 替身回归；体验版仍缺条件。
- **7.3 服务端子范围**：V11 承载冻结字段、提交幂等键、逐题结果及纯客观卷错题。完整最终答案、冻结、客观计分在同一事务；到期仅使用已确认答案。纯客观卷 `COMPLETED`，混合卷 `WAIT_SELF_GRADE`。每 5 秒截止调度与读取/保存/提交共用逻辑；统一账号→答卷锁顺序，获锁后取时钟，忙账号/答卷跳过。迟到保存的 409 不回滚冻结；迟到提交返回既有冻结结果。
- 实际 API 停机重启证据：只缩短自身隔离测试答卷截止，重启后先查数据库证明定时器冻结，再重连浏览器，离线答案未补写。没有修改系统时钟或旧迁移。

## 验证与运行

固定 Node **22.22.2**；项目镜像使用 Java **21**、Maven **3.9.12** 和 PostgreSQL **17.11**。本机默认 Node 24 / Java 17 不用于本轮验证。临时 Node/Chromium 位于忽略的 `tmp/toolchain/`，换机器不要依赖该路径；启动和复跑见 `deploy/README.md`。

```sh
node scripts/test.mjs
node scripts/test.mjs --negative-control --java-test=VersionIntegrationTest
npm --prefix web ci
npm --prefix web exec -- playwright install chromium
node scripts/test-browser.mjs
npm --prefix web run build
openspec validate build-online-exam-platform --strict
git diff --check
```

本轮最终代码：目标冻结测试 **5/5**，一键 Node **5/5**、Java **20/20**、空库迁移 **V1–V11** 通过；负向门禁预期退出 **1**。Chromium **158.0.8127.18** / Playwright **1.65.0** 真实流程 **6/6** 通过，两个 Web 构建通过。最终严格规格及文档检查见 `verification.md`。一键入口已自动纳入全部 `tests/*.test.cjs`，可用 `--java-test=类名#方法+方法` 跑目标测试。浏览器脚本使用随机项目、专属镜像/卷与随机端口，结束只清理自身容器/卷和 Vite，保留证据和构建镜像，不清理共享资源。

## 未完成与下一步

- **7.1、7.2、7.3 仍未勾选，本轮没有改 tasks 勾选，未提交、推送、合并或归档。** 用户要求先审阅证据，不得自行标记完成。
- 微信仍为 `touristappid`、`urlCheck=false` 与本地 HTTP。缺真实 AppID、登记的 HTTPS 域名、体验成员、真机和实际基础库版本，详见 `wechat-experience.md`。Web、服务端及小程序运行时替身都不能替代体验版。补真机 ET02、跨端恢复和后台行为后再讨论完整验收。
- 7.4/7.5 尚缺客户端题号导航、复查标记、服务端计时同步、提交确认及完整结果流程。当前只展示到期与未同步提示，没有交卷/自行批改页面。客户端时间变化仍缺证据。
- 第 8 组自行批改、空主观答案确认零分、最终成绩与混合卷错题/任务进度原子确认尚未实现。现有客观计分基础不代表第 8 组完成。6.4 任务逐卷状态仍待投影，当前任务列表不能作为完成状态证据。
- 容量、截止积压指标/告警、完整历史回看和发布验收仍待各自任务。不要从文件存在或局部测试推断整个平台可用。
- 接续建议：先审阅本轮 diff 和证据；可沿用此工作分支，保留未提交内容，再按实际依赖接客户端提交或第 8 组主观批改。新增迁移只能从 **V12** 开始，不改 V1–V11。

## 接续提示

> 请接手本仓库 `build-online-exam-platform`，保留当前分支与未提交修改。先查状态、最新提交和仓库规则，读本交接、verification 及 OpenSpec status/apply 的 contextFiles。当前进度 19/59，7.1/7.2/7.3 未勾选；真实 Web 保存/断网/冲突与实际 API 重启证据已补，微信体验条件仍缺。7.3 服务端提交/冻结及客观计分基础已实现，主观批改、任务投影和客户端提交流程仍待推进。使用 Node 22.22.2、Java 21、随机隔离 PostgreSQL，运行目标测试、一键回归、浏览器验证、严格校验和 diff 检查，更新 API/数据字典/场景/验证。不把替身当真机，不改旧迁移，不清理共享资源，不擅自勾选、提交、推送、合并或归档。
