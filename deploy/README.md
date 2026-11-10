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

## Web 和小程序入口

使用 Node.js 22.22.2。首次在仓库根目录执行 `npm --prefix web ci`，随后分别在两个终端执行：

```sh
npm --prefix web run dev:admin
npm --prefix web run dev:student
```

- [管理员登录](http://localhost:5173/admin/login)
- [学生注册](http://localhost:5174/student/register)与[学生登录](http://localhost:5174/student/login)

Vite 将同源的 `/api` 请求代理到 `http://127.0.0.1:8081`。如果调整了 API 端口，以 `EXAM_API_ORIGIN` 指定代理目标。`npm --prefix web run build` 分别在 `web/admin/dist` 和 `web/student/dist` 生成产物。

在微信开发者工具导入 `wechat/student` 可调试本地壳。开发配置的游客 ID 和本地 API 地址仅供模拟器使用。体验版需配置真实 AppID 和已登记的 HTTPS 请求域名，操作见[小程序体验版验收](../docs/changes/build-online-exam-platform/wechat-experience.md)。

如需启用本地管理员账号，在首次运行迁移容器前填写 `deploy/.env` 中的 `EXAM_BOOTSTRAP_ADMIN_PASSWORD`，长度为 12–72 个 UTF-8 字节。默认用户名为 `admin`。再次执行迁移不会覆盖已有启用管理员的密码。开发环境提供一个“演示年级”用于学生注册。管理员登录后可进入学科、题库、试卷和年级任务管理。学生登录后可从「练习试卷」查看同年级已发布的普通练习卷，并在「我的年级任务」查看发布时分配的卷。

## 测试

```sh
node scripts/test.mjs
node scripts/test.mjs --negative-control
```

第一条运行原演示、小程序页面逻辑和 Java 接口测试。第二条故意使 API 版本断言失败，应退出非零。脚本使用单独的 Compose 项目、随机数据库密码及独立数据卷，结束后清理对应资源。PowerShell 也可调用 `scripts/test.ps1`。

详见[验证记录](../docs/changes/build-online-exam-platform/verification.md)和[场景索引](../docs/changes/build-online-exam-platform/scenarios.md)。
