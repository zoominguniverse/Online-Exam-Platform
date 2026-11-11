# Web 与服务端部署、发布和恢复

本流程使用 Node 22.22.2、Java 21、PostgreSQL 17.11。镜像摘要见 Dockerfile / Compose；迁移账号与业务账号分离，`exam_app` 无建表权限。当前迁移为 V1–V13，V12/V13 由此前的学习记录与恢复实现添加；本轮 Web 范围 apply 未新增或修改迁移，后续从 V14 开始。迁移为向后兼容的新增表、权限和删除清单触发器，回退应用不回退数据库。

## 构建与启动

```sh
docker build -t exam-api:review backend
docker build -t exam-web:review -f web/Dockerfile .
# 将 deploy/.env.prod.example 复制到受保护的环境文件，填写随机机密及三个不可变镜像标签。
docker compose -p exam-prod -f deploy/compose.prod.yml --env-file deploy/.env.prod up -d web api-green
```

生产样例只把 Nginx 绑定到本机端口，DB 不发布端口。外层 HTTPS 入口负责证书和访问控制；生产 Cookie 必须通过 HTTPS 使用。不得把样例密码投入使用。`EXAM_SEED_DEV_GRADE=false`，演练才显式创建演示年级。API 与 Web 可独立构建，同一产物跨环境只替换配置。Web 使用 Nginx 1.30.6，带 CSP、nosniff、私有接口同源转发、SPA 深链接及动态服务发现。

`/livez` 检查应用进程；`/readyz` 同时检查数据库。数据库断开返回 503，就绪恢复后再引流；缺少必需机密时启动失败。API 优雅停止 30 秒，Compose 保留 40 秒，避免停止中断已经进入事务的提交。健康探针不返回连接串或机密。

## 蓝绿与上一版本

部署前保存当前 API/Web 镜像摘要、数据库兼容性和测试报告。将 `EXAM_GREEN_IMAGE` 指向候选构建，运行迁移并检查差异；不编辑已执行迁移。切换示例：

```sh
node scripts/release.mjs --project exam-prod --env-file deploy/.env.prod --target api-green --web-image exam-web:review
# 回退使用保存的上一版本 API 和 Web 镜像；蓝色镜像在环境文件中预先锁定。
node scripts/release.mjs --project exam-prod --env-file deploy/.env.prod --target api-blue --web-image exam-web:previous
```

脚本先启动并直接检查目标服务就绪，校验 Nginx 配置，保存上游到该项目自己的 routing 卷，reload 后等待边缘响应的 `X-Exam-Upstream` 确认。配置校验失败还原旧配置。替换 Web 镜像后读取同一 routing 卷，因此重建/备份重启不会静默切回旧 API。请同时把环境文件的 Web 镜像更新为选定版本，以便后续 Compose 操作使用同一产物。

回退保留新数据库和历史答卷。旧版本没有新增批改/消息页面时，回退窗口暂停这些新功能；原有作答与历史答案仍可读取。演练从真实 `c63f6e9` 构建上一版 API 和 Web，不能把“当前镜像换标签”当作回退证据。不可兼容的未来迁移须另外设计，不能套用本次兼容结论。

## 备份与恢复

```sh
node scripts/backup.mjs --project exam-prod --env-file deploy/.env.prod --directory /secure-backups/exam-prod-2026-11-11
node scripts/restore.mjs --project exam-recovery --env-file deploy/.env.prod --directory /secure-backups/exam-prod-2026-11-11 --deletion-ledger /secure-backups/latest-deletions.csv
```

备份只暂停指定项目中当时正在运行的 API，让在途事务结束；在写入暂停窗口生成自定义格式 pg_dump、私有附件 tar、删除清单和 SHA-256 manifest，随后恢复原先运行的服务并等待就绪。暂停期间写请求可能短暂失败，客户端保留草稿/原幂等请求。备份目录必须新建，脚本不覆盖旧备份。

恢复仅允许不同的明确项目，拒绝非空数据库和非空附件卷；校验三个文件摘要及 tar 成员名，恢复所有权，重放较新删除清单，清空 Web 会话和历史令牌，删除对应绑定并匿名化资料。删除清单应独立持续保存，必须选择最新版本，避免恢复使已删除用户重新出现。V13 数据库触发器保证旧应用回退期间删除账号也写清单。

随后以同一应用镜像启动恢复环境，切至经过验证的 API，重新登录并核对历史答案、成绩、图片后才开放流量。`restoreMillis` 是数据与文件恢复时间；演练另记 `recoveryMillis`，包含启动、登录和图片验证。RPO ≤24 小时，完整恢复 RTO ≤2 小时。

`deploy/backup.service.example`、`backup.timer.example` 给出每日香港时间 03:00 的部署样例，需运维安装并填入绝对路径、权限及外部机密。至少保留 7 天完整备份，异机加密存放；每天核查最新成功时间、摘要与磁盘容量，每周做隔离恢复。样例和脚本不会安装系统服务或自动删除共享备份。保留策略由值班运维核查并仅对所属项目执行。

## 观测与责任

管理员 `GET /api/v1/admin/observability` 返回滚动五分钟（最多10万样本）请求量、5xx 数量/比例、p95、数据库状态、Hikari 连接池等待/活跃/空闲数、到期未冻结数量及最大滞后。每 5 秒检查：数据库不可用立即告警；截止积压 >30 秒告警；5xx 比例持续 5 分钟 >5% 告警。请求和告警日志为单行 JSON，带 requestId、actorId、module、action、result、timestamp；不记录请求体、查询值、答案、密码、令牌或密钥。核心成功审计与业务事务一起提交，数据库不可用时仅输出安全故障信息。

责任角色是“值班运维”。收到数据库告警先核对就绪与 DB/磁盘，恢复连接后检查原截止未延长；截止积压告警按 requestId 和答卷状态定位锁等待/worker，读取及提交仍按原 deadline 冻结，禁止补写迟到答案；高错误率告警关联结构化错误码和最近发布，必要时切回上一版。禁止在处置中清理共享卷或打印机密。

JSON 请求最多 2 MiB；私有图片最多 5 MiB、1600 万像素。接口按认证主体每分钟限读 6000、写 3000；返回 429 与 Retry-After。登录另有持久化失败限制。滚动指标和限流为单实例内存口径，蓝绿各实例分别观测；跨节点集中采集由部署方接入日志平台。

## 可复跑演练

```sh
node scripts/test-operations.mjs
node scripts/test-mutations.mjs
node scripts/gate.mjs --scope web
node scripts/gate.mjs --scope platform
```

演练使用随机 `exam-release-test-*` 和 `exam-restore-test-*` 项目；只清理自己的容器和卷，保留镜像、备份和报告于忽略的 tmp。完整演练含真实上一版回退、一致性备份、删除清单、会话撤销、断库、在途提交及 100 活跃会话 600 秒。默认不缩短容量窗口；`--skip-capacity` 仅供排错，不算容量验收。当前平台范围即两个 Web 入口与后端；平台门禁在任务未完成审阅或必要技术检查失败时失败，不再依赖外部客户端证据。Web门禁用于技术验证，不能替代任务审阅。
