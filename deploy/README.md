# Java 开发环境

从仓库根目录使用 Docker 和 Docker Compose 启动。Java API 提供健康端点和三端共用的版本接口。

首次运行时，将 `deploy/.env.example` 复制成 `deploy/.env`，分别填入两个不同的本地随机密码。开发配置保存在 Git 忽略的文件中。

```sh
docker compose --env-file deploy/.env -f deploy/compose.dev.yml up --build -d
curl -fsS http://127.0.0.1:8081/actuator/health/liveness
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
curl -fsS http://127.0.0.1:8081/api/v1/version
```

首次构建需要下载依赖。API 端口默认只绑定本机的 8081，PostgreSQL 留在 Compose 网络里。`migrate` 容器用数据库迁移账号执行 Flyway，成功退出后才启动 API。API 使用独立的 `exam_app` 账号，默认关闭 Flyway。开发数据库保存在该 Compose 项目的命名卷中，现有卷继续使用初始化时的密码。附件保存在单独的 `attachments` 命名卷中。

在专用开发环境可用以下命令验证 PD02-F。数据库停机后，存活端点应返回 HTTP 200，就绪端点应返回 HTTP 503。恢复数据库后就绪应回到 HTTP 200。

```sh
docker compose --env-file deploy/.env -f deploy/compose.dev.yml stop db
curl -sS -i http://127.0.0.1:8081/actuator/health/liveness
curl -sS -i http://127.0.0.1:8081/actuator/health/readiness
docker compose --env-file deploy/.env -f deploy/compose.dev.yml start db
```

健康响应只暴露状态。版本响应的 `data.apiVersion` 为 `v1`。

## Web 入口

使用 Node.js 22.22.2。首次在仓库根目录执行 `npm --prefix web ci`，随后分别在两个终端执行：

```sh
npm --prefix web run dev:admin
npm --prefix web run dev:student
```

- [管理员登录](http://localhost:5173/admin/login)
- [学生注册](http://localhost:5174/student/register)与[学生登录](http://localhost:5174/student/login)

Vite 将同源的 `/api` 请求代理到 `http://127.0.0.1:8081`。如果调整了 API 端口，以 `EXAM_API_ORIGIN` 指定代理目标。`npm --prefix web run build` 分别在 `web/admin/dist` 和 `web/student/dist` 生成产物。


如需启用本地管理员账号，在首次运行迁移容器前填写 `deploy/.env` 中的 `EXAM_BOOTSTRAP_ADMIN_PASSWORD`，长度为 12–72 个 UTF-8 字节。默认用户名为 `admin`。再次执行迁移不会覆盖已有启用管理员的密码。开发环境提供一个“演示年级”用于学生注册。管理员登录后可进入学科、题库、试卷和年级任务管理。学生登录后可从「练习试卷」查看同年级已发布的普通练习卷，并在「我的年级任务」查看发布时分配的卷。

## 测试

```sh
node scripts/test.mjs
node scripts/test.mjs --negative-control
```

第一条运行原演示和 Java 接口测试。第二条故意使 API 版本断言失败，应退出非零。脚本使用单独的 Compose 项目、随机数据库密码及独立数据卷，结束后清理对应资源。PowerShell 也可调用 `scripts/test.ps1`。

一键入口自动包含 `tests/*.test.cjs`（含草稿回归）。Java 目标测试可传 `--java-test=IdentityIntegrationTest#方法名`，多方法用 `+` 连接。

真实浏览器验证使用同一 Node 22.22.2 和锁文件，首次安装 Chromium：

```sh
npm --prefix web ci
npm --prefix web exec -- playwright install chromium
node scripts/test-browser.mjs
```

脚本创建随机 `exam-browser-*` Compose 项目、专属 API 镜像、数据库/附件卷、随机本地端口和独立浏览器上下文，自动初始化测试账号与五题型卷；不使用已有开发库或真实账号。基础六项用例覆盖保存刷新、离线重连、版本冲突、响应丢失后继续编辑、跨标签换账号，以及实际 API 停机重启后的定时截止。重启用例只缩短自身测试答卷的截止时间，先从 PostgreSQL 确认调度冻结，再让客户端联网。证据、截图和 JSON 报告在 `tmp/exam-browser-*/evidence/`，包含测试数据，不包含会话凭据。结束只停止自身 Vite/容器并删除自身数据卷，保留证据及专属构建镜像，不清理共享资源。

截止调度默认启用，每 5 秒扫描最多 100 份答卷；获锁后确认时间，锁冲突留待后续扫描。`exam.deadlines.enabled=false` 仅用于可控时钟的接口测试以便显式触发 worker，开发/运行默认不关闭。应用重启后继续按数据库原截止时间处理；原截止时间不会延长。容量和截止积压告警仍在后续运维验收范围。

详见[验证记录](../docs/changes/build-online-exam-platform/verification.md)和[场景索引](../docs/changes/build-online-exam-platform/scenarios.md)。

## Web 与共用后端交付

学生 Web 已接入交卷确认、服务端锚定倒计时、结果与自行批改、记录、错题、资料/头像、消息和动态；管理端有全体成绩、群发消息/已读、日志和四项统计。正式任务勾选仍保留审阅，验收范围为管理端、学生 Web 与后端。

生产镜像、蓝绿切换、备份/恢复、限流和告警的完整步骤见[运行手册](../docs/changes/build-online-exam-platform/operations.md)。

```sh
node scripts/test-mutations.mjs
node scripts/test-operations.mjs
node scripts/gate.mjs --scope web
node scripts/gate.mjs --scope platform
```

Web 门禁运行 Node/Java、真实浏览器、双入口构建、契约/严格规格/diff，并核对此前独立运维容量与变异报告。完整运维演练需约12分钟，含100会话600秒和额外100份受控截止处理，报告保留于tmp；缺少这些证据时 Web 门禁失败。平台门禁仅在任务未完成审阅或必要技术检查失败时非零退出，不再要求任何外部客户端账号、域名或真机证据。原六项保存测试之外，现增加五题型完整创编→两任务卷交卷/批改/历史→消息/资料流程与页面故障恢复。

后台计时子场景启动一个独立的有界可视 Chromium，通过 `connectOverCDP({noDefaults:true})` 关闭默认焦点模拟，断言原生 `visibilitychange` 的 `isTrusted` 与隐藏/显示状态，再等待服务端取时。此场景需要桌面显示环境；无桌面的 Linux CI 可用 Xvfb。测试时钟在应用启动前安装，再仅改变页面日期，避免首次安装重置 `performance.now()` 干扰应用取样；不修改宿主时钟。子场景结束即关闭独立浏览器，其余流程继续使用常规隔离上下文。

若本机Docker JVM连接Maven Central发生TLS错误，但宿主能访问同一官方源，可使用Node22.22.2执行 `node scripts/gate-host-maven.cjs`。该可选命令启动临时本地只读传输，只转发官方Maven Central的公开依赖，保留Maven校验，门禁退出后关闭；不关闭证书验证、不切换第三方仓库。普通网络下仍直接用 `scripts/gate.mjs --scope web`。

完整浏览器回归现包含10项用例。scripts/check-browser-evidence.mjs进一步核对必需场景结果及原始JSON；缺失跨会话验收/公式/入口记录会使完整测试入口非零退出，不能仅用剩余测试通过代替。带--grep的目标测试仍可独立执行，不计为完整门禁。

Docker Hub临时不可达时，只在已经确认锁定摘要镜像可实际运行的主机上设置 EXAM_TEST_OFFLINE_IMAGES=1。Java/浏览器测试对自己的数据库设置pull_policy=never，运维脚本仅对随机exam-release-test-*/exam-restore-test-*项目使用--pull never；生产项目仍采用正常策略。缺镜像时明确失败，不换版本。浏览器就绪探针使用显式可引用的超时，避免Node在等待冷启动期间提前退出。
