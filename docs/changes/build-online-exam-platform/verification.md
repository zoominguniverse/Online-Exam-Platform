# 当前交付：53/53，完整平台门禁通过

截至香港时间 2026-11-11 16:35:03，build-online-exam-platform全部53项实施任务已按授权勾选，OpenSpec apply为all_done。本轮补齐原复核10项缺口；具体实现及证据见[收尾验收](completion-acceptance.md)、[最新报告](evidence/2026-11-11-completion/README.md)和[发布评审材料](release-review.md)。

完整平台门禁（非仅Web子范围）通过：Node3/3、Java28/28、真实Chromium10/10、双入口构建、73操作契约、30需求/71场景引用、严格规格与diff。45入口三宽度、16空视图、35失败恢复、14写失败/重复抑制及完整键盘答题均有记录。最终镜像回退/恢复通过，同后端既有100会话600秒容量及两项真实代码变异证据由78个后端文件SHA-256一致性复核支持。

当前分支仍为codex/build-online-exam-platform-7-2，参考HEAD c63f6e9；修改未提交。V1–V13不改，后续迁移从V14开始。不提交、推送、合并、归档或生产部署；旧五项规划和主规格整理仍属后续工作。生产HTTPS/外部机密/值班/定时备份配置及既有依赖公告见发布评审材料，不将本地验收说成已上线。

## 以下为此前复核与实施历史

# 当前任务复核：43/53

用户已授权勾选。本次按代码和既有成功证据新增勾选26项；当前apply为ready，仍有10项未完成：4.4、5.4、6.2、9.3、10.1、10.2、10.5、11.1、12.4、12.5。具体缺口和已勾选依据见[任务复核](task-review.md)。上一轮将36项统称“仅待审阅”不准确，现已更正：既有Web门禁通过不能替代缺失的指定场景验收。

本次不修改业务代码，不重复宣称执行全部业务回归；既有Node3/Java28/Chromium9和容量/发布恢复报告仍有效于其明确覆盖范围。后续可以按本次授权，在完成指定验证后更新勾选；提交、推送、合并和归档仍未授权。

## 以下为本次授权前的实施记录

# 当前验证：Web 范围 apply

本节是范围变更后的入口；下文报告均保留其原始生成时间和范围，不覆盖为本轮结果。当前change只有管理端、学生Web和共用后端，任务17/53，未改变勾选。

移除旧身份入口与客户端，全部个人接口只接受Cookie、所有写入校验CSRF；保留历史表的凭据撤销及删除/恢复兼容。新增回归直接在真实PostgreSQL插入历史令牌，确认即使与合法Cookie一起发送也不能认证、原接口被拒绝，改密及删除仍清理旧凭据。两独立Web会话覆盖并发开始、恢复、保存和改密撤销。

入口版本/网络重试与表单失焦校验已纳入真实Chromium；随机端口改用浏览器安全的动态端口段，避免6566等端口造成探针超时。JSON大小限制同时覆盖大小写媒体类型与+json。新技术门禁、负向断言、关键变异和最新容量/恢复结果见[当前验收报告](evidence/2026-11-11-web-scope/README.md)。发布门禁不再依赖外部客户端证据，只保留任务审阅及技术检查。


最终 Web 门禁于香港时间 2026-11-11 15:54:15 完成：Node **3/3**、Java **28/28**、Chromium **9/9**，零失败/跳过/重试；双入口构建、73个操作契约、30项Requirement/71项Scenario索引、严格规格及diff检查通过。真实后台切换观察到可信隐藏/显示事件，返回后服务端重新取时，日期偏移到2040仍显示19:59。成功桌面/375px截图已打开检查。统一门禁项目 exam-gate-1794383522594、浏览器 exam-browser-412f463751。

100会话容量及100份截止处理、两项真实计分/授权变异、故意版本不匹配的真实JUnit负向控制均有本轮成功记录。最终Web镜像另经 exam-release-test-877c533fc3 重新执行配对发布/回退/恢复（不重复容量），全部通过。只清理各次自己的随机项目/卷。V1–V13与之前快照SHA-256一致；当前apply仍ready、17/53，36项仅待审阅，未勾选、提交、推送、合并或归档。已知依赖公告仍按 dependencies.md 作为发布前评审项，技术门禁通过不代表发布批准。

## 以下为原范围验证历史

# Java 基础验证记录

## 当前验收：2026-11-11 Web 与共用后端

用户追加要求继续所有与小程序无关部分。实现范围、明确排除的微信部分及任务映射见 [交付矩阵](web-backend-delivery.md)。基线 `c63f6e9`、分支 `codex/build-online-exam-platform-7-2`；未提交/推送/合并/归档，任务勾选保持19/59。下文较早阶段记录保留历史，不应把当时的“尚未实现”当作当前状态。

本轮添加V12/V13；批改、错题、任务进度、历史回看、资料头像、消息、审计、香港统计与Web页面已实现。失败注入覆盖逐题分数之后及错题写入阶段的整笔回滚；终态相同评分重试返回原结果，修改已确认分数409。新增最低权限真实exam_app集成链路，包含删除触发器。

最终统一Web门禁于香港时间14:49完成：Node5/5、Java29/29、Chromium8/8、双入口构建、77个操作契约/场景索引、严格规格与diff检查通过。管理23/学生15个路由各测375/768/1440，另测作答页；32个数据页加载/失败/重试恢复，最小正文对比度7.138:1，键盘焦点3px。已打开最终正常结果及窄屏截图复核。此次修复数据库故障日志与MDC重复字段导致的日志丢失，回归解析实际JSON错误行验证主体/requestId/SQLState与脱敏。容量/运维报告生成于上午，日志修复后复跑全套功能门禁，没有将两个报告的测量时间混为一轮负载。

完整运维演练 `exam-release-test-40cd70bf92` 已通过：真实c63f6e9旧API和旧Web回退、浏览器原答案、在途交卷一次提交、备份、独立恢复、较新删除清单、旧Cookie拒绝、历史成绩/答案/私有图片及断库健康恢复。数据恢复5758ms；RPO12.231秒；含应用启动、重新登录和图片核对的完整恢复15702ms，满足24小时/2小时目标。只操作并清理本轮两个随机项目，没有共享资源清理。

容量在真实Windows宿主 AMD Ryzen7 9700X/16逻辑CPU/约16.23GB内存、Linux Docker 引擎上运行，API限制2CPU/4GiB，DB限制2CPU/2GiB；103用户、最终202答卷。100活跃会话稳定600秒，6000次列表、12000次保存、100次交卷，成功率100%；p95为5.581/11.471/335.422ms，全部低于500/1000/1000ms预算。额外100份自有测试答卷受控缩短截止，不改系统时钟、不发HTTP补处理，worker冻结p95 1588.939ms、最大1600.191ms。总测量约608秒，受控截止相位与稳定负载相位分开，不冒充自然等待20分钟或真实微信后台。

`scripts/test-mutations.mjs` 在tmp源码副本中分别把多选比较改为恒真、移除答卷owner过滤，两个真实目标测试均断言失败/退出1；生产源码未被变异。OSV重新核对实际打包59个坐标，仍有6组件/22条公告，新增AOP未命中；许可证及锁文件见 dependencies.md。

最终Node/Java/浏览器、三宽度/对比度/故障恢复明细、Web门禁和预期失败的平台门禁结果统一保存在 [当前验收证据](evidence/2026-11-11-web-backend/README.md)。真实微信缺口仍阻塞全平台验收，不能用本轮Web门禁替代。

## 2026-11-11 本轮接手：7.2 真实 Web 与 7.3 服务端子范围

接手时实际分支为 `main`，工作区干净，HEAD 为 `c63f6e9de010493f8c4856189087a4fbf52ef4a6`，与本地远程跟踪一致。新建工作分支 `codex/build-online-exam-platform-7-2`，未提交、合并、推送或归档。已读仓库交接、README/部署规则和 apply 返回的全部 proposal/design/tasks/10 项 specs。OpenSpec `spec-driven`、apply `ready`，进度 **19/59**；按用户要求不更改任务勾选。

本机使用经官方 SHA-256 校验的独立 Node **22.22.2** / npm **10.9.7**，Docker **29.7.2** / Compose **5.5.1** Linux 引擎，项目摘要固定的 Maven **3.9.12** / Temurin **21.0.10** 构建、Temurin **21.0.11** 运行和 PostgreSQL **17.11**。未切换全局 Node 24 / Java 17。测试均使用随机项目、随机测试密码、独立数据库/附件卷；仅停止自身进程并清理自身容器和卷，未执行共享资源清理。

### 7.2 真实浏览器

新增固定 Playwright **1.65.0** 和 `scripts/test-browser.mjs`，实测 Chromium **158.0.8127.18**。`node scripts/test-browser.mjs` 最终 **6/6** 通过；真实 Vue→Java API→PostgreSQL，截图和直接数据库摘要见 [Web 证据](evidence/2026-11-11-web-saving/README.md)。其中 5 项是保存/断网/冲突/账号隔离路径，第 6 项是 7.3 的实际 API 进程重启验证。没有将微信替身计为真机证据。

首轮复现 Vue 响应式对象不能直接 `structuredClone` 而导致保存中止；改为对纯 JSON 答案取副本。另修复清空最后一个多选、未知响应后拿新草稿误判冲突、冲突内容核对/显式重读、账号切换后延迟响应恢复旧内容，以及到期核对后未刷新答卷元信息。草稿同时保存待确认请求副本，未知结果先与该副本核对，再发送较新输入；刷新也保留此信息。小程序同步相同请求副本/清空语义，但仅运行 Node 替身回归，仍无体验版证据。

### 7.3 服务端

仅新增 **V11**，不修改 V1–V10。新增提交幂等记录、冻结字段、逐题结果及纯客观卷完成时的错题存储；手动提交使用完整最终答案与 `expectedVersion`，在事务内保存、冻结及计分。混合卷停留 `WAIT_SELF_GRADE`，主观题和最终分数为空。自行批改、任务逐卷完成投影、结果回看和客户端提交页面仍未实现，不能据此勾选 7.3 或第 8 组。

调度每 5 秒最多取 100 份到期候选，HTTP 和 worker 统一先锁账号再锁答卷，worker 对忙账号/答卷 `SKIP LOCKED`。取得锁后用可注入服务端时钟重新取时刻，读/保存/提交也执行同一到期检查。迟到保存返回 409 `EXAM_CLOSED`，但该异常不会回滚已经完成的冻结；迟到提交返回按既有答案生成的唯一结果。同键异载荷/答卷冲突，24 小时后仍由冻结状态保护一次性效果。

目标命令为 `node scripts/test.mjs --java-test=IdentityIntegrationTest#submitsFinalAnswersAtomicallyAndRetriesTheFrozenResult+freezesPersistedAnswersOnReadAndLateSaveWithoutRollingBackTheFreeze+serializesManualDeadlineCompetitionAndRecoversOverdueDrafts+concurrentManualSubmissionsReturnOneResultWithoutDuplicatingScores+deadlineWorkerSkipsBusyOwnerAndUsesTheSameLockOrderAsHttp`，最终 **5/5** 通过。真实 HTTP、独立 PostgreSQL、可控时钟和独立并发请求覆盖非法最终载荷整体拒绝、逐题结果插入失败整笔回滚、行锁等待跨过截止、迟到保存冻结不回滚、两个 worker/提交竞争、幂等重试、忙账号跳过、纯客观完成后独立重练及过期键不改终态。0.10+0.20=0.30，主观待批改不提前生成最终错题。

浏览器重启用例实际停止自身 API 容器，在停机期间将自身答卷截止缩为开始后 1 ms，然后启动同一 API。客户端一直离线，先直接查数据库确认定时扫描已冻结，再联网核对：仅停机前已确认答案存在，原数据库截止不延长，新草稿未补写。此项验证本地进程恢复，不代表机房故障或微信真机验收。

### 收尾检查与保留缺口

`node scripts/test.mjs` 最终 Node **5/5**、Java **20/20**、零失败/跳过，空库 Flyway **V1–V11** 成功。一键入口现自动纳入全部 `tests/*.test.cjs`，不再遗漏草稿测试。`node scripts/test.mjs --negative-control --java-test=VersionIntegrationTest` 故意破坏版本断言，实测按预期退出 **1**，证明门禁仍传播失败。最终两个 Web 入口构建、`openspec validate build-online-exam-platform --strict` 和 `git diff --check` 均通过；OpenAPI YAML 解析无错误、26 个 operationId 唯一、166 个本地引用全部解析。此项为 YAML/引用检查，不是外部 OpenAPI 语义认证。最终 apply 仍为 `ready`、19/59，规格文件和 V1–V10 无 diff。最后几轮专属项目的容器与卷已按 Compose 标签只读确认不存在。npm 审计仍为既有 **1 中危/1 低危**，新增三项 Playwright 依赖均为 Apache-2.0，未自动升级既有依赖。

微信配置仍为游客 AppID、关闭域名校验和本地 HTTP 地址，未取得真实 AppID、合法 HTTPS 域名、体验成员、真机/基础库版本。详见 [体验版阻塞](wechat-experience.md)。**7.1、7.2、7.3 均保持未勾选，apply 为 19/59**；Web 证据不代替微信，服务端冻结不代替完整批改/客户端计时闭环。

## 2026-11-11 任务 7.2 续接：答案保存与待同步草稿（部分证据）

本轮沿用工作分支及未提交修改。V10 在独立 PostgreSQL 17.11 空库迁移通过。聚焦命令在独立 Compose 项目执行 `mvn -B -ntp -Dmaven.repo.local=/opt/maven-repository -Dtest=IdentityIntegrationTest#savesWholeAnswerSetWithVersionAndDeadlineChecks test`：最终 1/1 通过。该测试通过真实 HTTP 与数据库验证五题型保存、按快照校验、整体拒绝非法选项、全量遗漏清空、旧版本冲突、同版本两会话并发仅一次成功、本人跨 Web/微信令牌读取以及精确截止时刻拒绝。另以数据库锁阻塞保存请求，确认请求取得锁后若已到截止时刻则不能写入。微信身份提供器为替身。

使用 Node 22.22.2 执行 `node --test tests/*.test.cjs`，5/5 通过；其中新增 `tests/attempt-draft.test.cjs` 以小程序运行 API 替身验证保存中继续编辑、未知提交结果先读服务端、版本冲突保留草稿、退出后清理账号草稿。使用项目锁文件和当前命令 HTTP 代理安装 Web 依赖；`npm --prefix web run build` 的管理端与学生端均构建通过。`node scripts/test.mjs` 的固定 Node 测试入口仍只含原 4 项，另执行真实 PostgreSQL Java 接口回归；其本轮最终结果见收尾记录。

收尾复跑 `node scripts/test.mjs`：Node 固定入口 4/4、Java 15/15，Flyway V1–V10 通过，随机 Compose 项目与自身卷已清理。补充的非法题目、重复题目、错误题型、超限简答、未登录及管理员拒绝、截止前一毫秒与锁等待跨过截止时刻断言均包含在 15 项 Java 测试中。`openspec validate build-online-exam-platform --strict` 与 `git diff --check` 均通过。

学生 Web 与小程序已连接开始、读取、自动保存和本地待同步状态；草稿键含账号与答卷 ID，服务器确认前不清除，版本冲突时保留本地内容并读取服务端版本。尚未取得浏览器实际作答、断网重连及微信体验版真机证据；Web 的乱序、账号切换和客户端时间变化也缺独立自动化覆盖。7.2 保持未勾选，7.1 的保存后跨认证读取已有后端证据，但完整 ET01 客户端与截止冻结仍未验收。

续接收尾：修正未知保存结果已在服务端成功、但等待核对期间用户再次编辑的状态处理，先确认旧答案的服务器版本，再保留新草稿继续同步；同时避免 Web 页面卸载或账号切换后的异步读取写回旧状态。Node 22.22.2 重新执行 `node --test tests/*.test.cjs`，5/5 通过；`npm --prefix web run build` 的两入口均通过；`openspec validate build-online-exam-platform --strict` 和 `git diff --check` 通过。Chrome 无头模式实际打开 `/student/papers`，渲染了学生登录页面并触发受保护路由跳转；该检查未执行登录后的答题、断网与冲突流程，不能作为 ET02 客户端验收。浏览器控制插件初始化报 `failed to write kernel assets: 系统找不到指定的路径 (os error 3)`。本轮临时 `exam-browser-72-codex` API/PostgreSQL Compose 项目、专用卷和 Vite 进程已单独停止并清理，未清理其他项目资源。

最终代码再次执行 `node scripts/test.mjs`：固定 Node 4/4、Java 15/15、零失败；PostgreSQL 17.11 空库迁移 V1–V10 成功，脚本移除其随机命名测试容器与数据卷。小程序答案对象核对改为按题目标识排序后，另复跑 `node --test tests/*.test.cjs`，5/5 通过。

## 2026-11-11 任务 6.3：任务卷并发开始与恢复

沿用 `codex/build-online-exam-platform-5-3` 分支及已有未提交修改。以 Node 22.22.2、Docker Linux 引擎、Maven 3.9.12 / Java 21 和独立 PostgreSQL 17.11 Compose 项目运行。Flyway 从空库顺序应用 V1–V9。新增 V9 外键固定任务卷版本及冻结成员，V8 的 `(owner_id,paper_id) WHERE task_id IS NOT NULL` 部分唯一索引保护任务卷一次性；普通卷仍可新建多份。

目标命令为隔离 Compose 中执行 `mvn -B -ntp -Dmaven.repo.local=/opt/maven-repository -Dtest=IdentityIntegrationTest#startsFrozenTaskPapersOnceAcrossWebAndWechat test`，最终 1/1 通过。完整命令 `node scripts/test.mjs` 在最终代码上通过：Node 4/4、Java 14/14、失败 0、跳过 0。脚本只删除本轮随机 Compose 项目及对应数据库卷。`openspec validate build-online-exam-platform --strict` 与 `git diff --check` 均通过；apply 进度为 19/59。

目标测试经真实 HTTP 认证链路验证 Web Cookie 与微信令牌同键、不同键并发，两个独立请求以闸门同时发出，响应同一答卷 ID；数据库仅一份答卷和一组题目快照。直接重复插入触发 PostgreSQL 唯一约束。还验证冻结成员逐卷开始、不同成员隔离、发布后新增成员拒绝、转年级后原成员保留资格、无关联卷与伪造任务 ID 拒绝、不落部分记录、发布后试卷换版仍用冻结版本、归档后未开始拒绝及已开始恢复、本人读取和安全 DTO。微信身份提供器为测试替身，此结果仅证明服务端双认证路径，不代表微信真机验收。

开发中一次扩展测试因将转年级成员用于普通卷回归而得到 404；改由原年级成员执行该断言后完整回归通过。随后发现同键配伪造任务 ID 可能误取旧答卷，已补校验及断言，并在最终代码上重跑完整回归。7.1 的任务卷开始与未提交答卷恢复已有后端证据；答案保存后的跨端恢复属于 7.2，访问时到期冻结属于 7.3，完整 ET01-N/ET01-B 与完成后重练仍待后续验证。

## 2026-11-11 续接实测：5.3 与普通卷开始子范围

重启后 Docker Desktop 29.9.3 的 Linux 引擎及 Compose 5.7.0 可用。Docker Desktop 的系统 HTTP 代理无法拉取 `registry-1.docker.io`（EOF）；宿主机经同一 HTTP 代理对该域名在 TLS 握手失败，直连超时。经代理访问同一 Docker 官方 Registry 的 `index.docker.io` 返回 401 正常挑战，预取项目固定摘要的 PostgreSQL 17.11、Maven 3.9.12/Java 21 和 Temurin 21 镜像成功，未改镜像来源或版本。Docker 显示的代理为内部地址 `http.docker.internal:3128`，未把宿主机 `127.0.0.1` 直接用于容器。

在当前进程 PATH 临时加入已校验的 Node 22.22.2 和 Docker CLI，执行 `node scripts/test.mjs`：原演示和小程序 Node 测试 4/4 通过；隔离 PostgreSQL 17.11 完成 Flyway V1–V7 迁移，Java 接口测试 12/12 通过（包括上一轮新增的类型、状态、时段边界与异年级断言）。脚本清理随机 Compose 项目及对应数据库卷。

新增 V8 答卷开始迁移及普通卷开始/读取接口后再次执行同一命令：Flyway V1–V8 通过，Java 21 编译通过，Java 接口测试 13/13 通过，Node 4/4 通过。进一步加入同账号两会话并发同键测试后第三次执行同一命令：Java 13/13、Node 4/4，再次通过；两个并发响应返回同一个答卷 ID，数据库只新增一份。每次均使用新的随机 Compose 项目，结束后只清理自身容器与卷。

`startsOnlyEligibleOrdinaryPapersAndFreezesSafeSnapshots` 用可注入时钟验证固定卷、时段卷开放/截止边界、无资格拒绝、拒绝不落库、同键重试与换卷冲突、新键独立答卷、题目改动后的旧快照、私有答案不出现在响应、本人读取和他人 404。5.3 的 PA02-F / PA02-B 验收已覆盖。7.1 仅完成普通卷后端开始及读取子范围；任务卷恢复、答案保存后的跨端恢复、截止冻结、提交和纯客观卷完成后重练仍待后续任务，不能将当前 `IN_PROGRESS` 响应视为到期冻结结果。

## 2026-11-10 本轮接手：任务 5.3（未完成）

### 后续环境恢复尝试（同日，任务 5.3 / 7.1 仍未完成）

- 保留 `codex/build-online-exam-platform-5-3` 分支及上一轮五个文件的修改。官方 Node 22.22.2 Windows x64 包经官方 SHASUMS256 校验一致；执行该版本的 `node --test tests/exam.test.cjs tests/wechat.test.cjs`，4/4 通过。
- 宿主机为 Windows 10 Home China 22H2 build 19045 x64，WSL 程序版本为 3.0.5，但 `wsl --status` 仍报告缺少 Windows 可选组件。尝试 `wsl --install --no-distribution` 和启动提升权限的安装进程后，复查仍未生效；需要本机管理员确认及可能的重启，未擅自重启。
- Docker 官方下载站通过 HTTP 代理 `127.0.0.1:7897` 可连接。安装包约 595 MiB，当前代理速率约 0.5 MiB/s，下载在约 88 MiB 时中止；残留文件仅为不完整下载，不能运行。Docker CLI、Linux 引擎及 Compose 尚不可用，`node scripts/test.mjs` 的 PostgreSQL 阶段和上一轮新增 Java 断言仍未执行。未新增 7.1 Java 代码以免积累未经编译和数据库验证的实现。

### 同日续传与安装结果

- Docker 官方地址对字节范围请求返回 HTTP 206，已从 93,237,248 字节续传到完整 623,969,712 字节。Windows Authenticode 验证 `Valid`，签名主体 `Docker Inc`，SHA-256 为 `A5E299131C9C53D60F3F752B162078945EB5FAF6EEDCF0BE20A214C6E6CEA444`。按官方单用户方式安装；Docker CLI 29.9.3、Compose 5.7.0 可运行。
- 已启动 Docker Desktop，但 `docker info` 返回 `Docker Desktop is unable to start`。当前 Docker 日志明确指出 `WSL_E_WSL_OPTIONAL_COMPONENT_REQUIRED`，并提示必须重启以完成 WSL 安装。用户已确认执行管理员 WSL 操作但尚未重启。未擅自重启。Linux 容器引擎、镜像拉取、隔离 PostgreSQL 测试仍待重启后验证。
- 工具准备目录为 `C:\Users\86158\AppData\Local\Temp\online-exam-platform-tool-prep`。重启后先确认 `wsl --status`、`docker version` 服务端、`docker info` 的 Linux 引擎和 `docker compose version`；再将该目录内 Node 22.22.2 临时加入当前进程 PATH，执行 `node scripts/test.mjs`。基线通过后实施普通卷开始接口及真实 PostgreSQL 集成测试。

工作分支 `codex/build-online-exam-platform-5-3`，基线 HEAD `85cb2c0`。现有 Docker CLI 不在 PATH；常见 Docker Desktop 安装目录、卸载登记和运行进程均未找到可执行文件，`wsl --status` 报 WSL 可选组件缺失。代理 `127.0.0.1:7897` 的 TCP 端口可连接，未据此推断镜像仓库可访问。Node 为 24.21.0，项目要求 22.22.2。

授权环境执行 `npm test`：4/4 通过。`node scripts/test.mjs` 的历史本轮复核结果为 Node 4/4 通过、随后 `spawnSync docker ENOENT`，未进入 PostgreSQL 接口测试。本轮新增的试卷列表断言尚未在 PostgreSQL 执行，不记作通过。`git diff --check` 用于检查改动格式。

现有个人试卷查询已按学生年级、发布、归档、卷型和服务端时钟筛选元信息。本轮补充类型与状态组合、左闭右开时间边界和异年级列表断言。PA02-F 与 PA02-B 还要求检验开始答卷请求；普通卷开始和快照属于 7.1，任务卷开始属于 6.3。完成这些接口并在隔离 PostgreSQL 验证前，5.3 保持未勾选。

2026-11-10，在从 `efb5db2` 建立的工作区上使用 Docker Desktop 的 Linux arm64 容器验证。范围为构建依赖和最小运行环境。

| 场景 | 实测结果 |
| --- | --- |
| Java 干净构建 | Maven 3.9.12、Temurin 21.0.10 编译成功，Spring Boot 3.5.16 打包；运行镜像使用 Temurin 21.0.11 |
| 空库迁移 | PostgreSQL 17.11 上 Flyway V1 记录成功，创建 `grades`。第二次执行校验通过且无需迁移 |
| 数据库权限 | API 账号 `exam_app` 可连接。查询 `has_schema_privilege(..., 'CREATE')` 返回 `false` |
| 正常及断库状态 PD02-F | 在线时存活和就绪均返回 `200 UP`。数据库停止后就绪返回 `503 DOWN`，存活仍返回 `200 UP` |
| 恢复及缺失配置 PD02-F | 数据库重新启动后，同一个 API 容器恢复 `200 UP`。缺少数据库配置时进程退出码为 1，只报告缺失键名 |
| 原演示回归 | `npm test` 的 3 项测试通过 |

本机复跑见 [Docker 开发环境](../../../deploy/README.md)。首次启动验证日志在 `backend/target/foundation-health.log`。首次 Java 服务启动时仅有运行探针，后续身份阶段加入年级 Mapper。

前端锁文件干净安装、Maven 解析清单、许可证和安全公告见 [构建依赖与审计](dependencies.md)。验证结束后已清理专用 Compose 项目及临时数据卷。

## 客户端入口和测试脚本

- 使用独立 Compose 项目从空卷启动，检查存活、就绪及 `GET /api/v1/version` 的 `v1` 响应，登记 PD02-N 的启动子场景。
- 使用 Node 22.22.2 构建两个 Vue 入口。在 Chromium 打开两个入口，均显示 Java API 返回的 `v1`。中断版本请求后均显示错误，重试可恢复，未知路径可以返回入口。
- 在 JavaScript 运行时替换微信网络接口并调用真实 Java API，小程序页面逻辑通过版本显示状态、请求失败及重试验证。开发者工具渲染及体验版仍待验证。
- `node scripts/test.mjs` 在隔离 PostgreSQL 运行原演示 3 个测试及版本接口测试，全部通过。`--negative-control` 故意给版本断言传入错误值，退出码为 1。两次运行均清理各自的容器和数据卷。PowerShell 包装脚本转发参数与退出码，本机未运行 PowerShell。

## Web 身份闭环

- 使用独立空卷执行 Compose。Flyway 执行账号迁移，开发配置初始化管理员和演示年级，Chrome 通过使用 `exam_app` 的运行容器完成真实 API 流程。
- 在 Chrome 的两个独立会话中验证学生注册登录、管理员登录、两个账号列表、学生改名、禁用、启用、重新登录及退出。管理员不能进入学生页，学生不能进入管理页。匿名访问受保护页跳转登录，未知路径可返回版本入口。
- 在隔离的 PostgreSQL 测试库验证 CSRF、Cookie 安全属性、注册不接受角色字段、规范化唯一、密码不出现在响应或测试请求日志、软删除及两个管理员并发操作。管理员并发操作后一人保留启用身份。
- 本机可复跑接口测试。浏览器人工复核从管理端 `/admin/login`、学生端 `/student/register` 开始，分别使用独立浏览器会话。

## 微信身份本地验证

- 使用替身模拟微信的成功、无效 code 和超时。真实 PostgreSQL 验证并发绑定仅一个成功，原绑定保持、解绑后全部旧令牌失效、旧 code 无法重放，以及小程序与 Web 具有相同用户 UUID。
- `tests/wechat.test.cjs` 通过模拟小程序运行时验证退出后清理内存中的令牌及资料。基础库、体验成员和真机结果待登记。

## 题库、试卷和年级任务

- 在 Chrome 通过实际运行镜像创建学科与五题型，上传并预览私有 PNG，筛选和归档题目。将五题型加入同一试卷并预览、发布、归档，学生端在发布后看见试卷，归档后练习列表移除该卷。
- 管理员在 Chrome 发布包含两张卷的年级任务，目标学生可在详情看到两项。发布后注册的同年级学生直接访问同一地址时收到「任务不存在」。
- PostgreSQL 接口验证五题型字段错误、附件类型与大小、私有读取、题目版本保留、三类卷发布、版本冲突、时段边界及试卷发布中途失败回滚。年级任务验证了成员冻结、卷唯一绑定、归档保留和整批回滚。
- 小程序运行时替身验证练习卷状态和香港时间转换、任务详情显示两张卷以及收到拒绝后清空前一份数据。真实客户端渲染仍待验收。
- 手工复核从管理端 `/admin/subjects`、`/admin/questions`、`/admin/papers` 和 `/admin/tasks` 进入。学生端登录后从「练习试卷」及「我的年级任务」查看结果。Chrome 验证使用独立开发数据库和附件卷，验证结束后清理了这两个临时卷。
