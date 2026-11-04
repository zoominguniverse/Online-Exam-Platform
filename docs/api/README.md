# API 契约与基础运行

资源清单为 [openapi.yaml](openapi.yaml)，采用 OpenAPI 3.1，以合法 YAML 的 JSON 子集保存。接口、DTO 和文档从 backend/src/interfaces/routes.ts 同源生成，`npm run check --prefix backend` 核对一致性。

当前已实现：独立 HTTP 后端、PostgreSQL 迁移和事务、注册与会话、管理员账号管理、试卷读取、开始/恢复、草稿、提交、到期冻结、自行批改、本人历史与错题，以及独立 Outbox/到期扫描进程。演示前端尚未接入 API；管理端页面、资料头像、创编、任务发布、附件、消息和完整生命周期仍按后续任务实施。三类试卷/五题型合成数据通过受控开发初始化创建，不能替代创编接口验收。

## 配置与启动

需要 Node.js 24+ 和独立 PostgreSQL。执行 `npm ci --prefix backend`。backend/.env.example 仅列键名；从环境注入完整配置，或在 shell 加载自己创建且被忽略的本地 .env。程序不自动读配置文件，不提供默认数据库、账号或密码。

必需键：DATABASE_URL、APP_ORIGIN、CORS_ALLOWED_ORIGINS、PRIVATE_STORAGE_ROOT、OUTBOX_ENABLED、ACCESS_TOKEN_SECRET、LOG_LEVEL、RATE_LIMIT_MAX、HOST、PORT。来源为 HTTPS origin，白名单用逗号分隔且不能用通配符；PORT 为 1–65535，限流为每 IP 每分钟正整数上限，私有存储根为绝对路径，签名密钥至少 32 字符，Outbox 开关为 true/false。缺失或非法配置启动失败，只显示键名。

1. 用迁移角色配置 DATABASE_URL，执行 `npm run migrate --prefix backend`。重跑无重复对象，历史摘要变化失败。
2. 开发库名用 exam_dev、地址为 localhost/127.0.0.1；显式注入 DEV_SEED_ALLOWED=true 后执行 `npm run seed --prefix backend`，输出合成年级和试卷 UUID。每次新增独立合成内容，不适用于生产。合成时段窗口为 2026-01-01 至 2027-01-01 UTC；固定练习无窗口限制。
3. 学生以输出的 gradeId 调用注册。初始管理员只能离线执行 `npm run create-user --prefix backend`，注入 BOOTSTRAP_USERNAME、BOOTSTRAP_PASSWORD、BOOTSTRAP_ROLE 和可选 BOOTSTRAP_GRADE_ID；学生必须有有效年级。命令只输出用户 UUID/角色，之后移除临时凭据键。
4. 换用无 DDL 权限的运行角色配置 DATABASE_URL，执行 `npm start --prefix backend`；另开进程执行 `npm run worker --prefix backend`。
5. 浏览器通过同站 HTTPS 网关访问 /api/；前后端仍独立构建部署。当前仓库不自动建立 HTTPS 网关，真实浏览器另行验收。

rollback 每次只回退最新迁移，只接受显式 DISPOSABLE_DATABASE=true 的 `npm run rollback --prefix backend`。002 回退保留账号与历史、移除新守卫/索引，之后管理写入口失败关闭；继续回退 001 会删除基础数据，不能无损恢复。生产回退须按迁移专属条件执行。字段、权限和迁移见 [数据字典](../data/dictionary.md)，事件补偿见 [Outbox 手册](../operations/outbox.md)。

## 请求与响应

所有响应 `{code,message,data,requestId}`。OK 才是业务成功，HTTP 映射独立检查；requestId 由服务器生成。错误不含堆栈、SQL、配置值或输入全文。所有下列路径均以 /api/v1 为前缀。

| 操作 | 方法和路径 | 请求 |
| --- | --- | --- |
| 注册 | POST /users | username、password、gradeId、Idempotency-Key；只能创建学生 |
| 管理员新增账号 | POST /users | 管理员 Bearer、幂等键、username、password、role、gradeId；显式 role 选择管理分支 |
| 管理员查询账号 | GET /users；/users/{id} | 管理员 Bearer；白名单筛选、分页及安全详情 |
| 管理员修改账号 | PUT /users/{id} | 管理员 Bearer、幂等键、version、username、role、gradeId |
| 禁用账号 | POST /users/{id}/suspensions | 管理员 Bearer、幂等键、version；撤销全部会话 |
| 删除账号 | DELETE /users/{id} | 管理员 Bearer、If-Match 的带引号版本，如 "1"；匿名化并撤销全部会话 |
| 登录 | POST /sessions | username、password、幂等键和允许的 Origin；返回 accessToken、sessionId、csrfToken、user |
| 刷新 | POST /sessions/refreshes | 空 JSON 对象、刷新 Cookie、Origin、X-CSRF-Token；单次轮换 |
| 退出 | DELETE /sessions/{id} | 本人 Bearer、Origin、X-CSRF-Token |
| 改密 | POST /users/{id}/password-changes | 本人 Bearer、幂等键、currentPassword、password |
| 试卷读取 | GET /papers；/papers/{id} | 学生 Bearer；不返回参考答案 |
| 开始练习/任务 | POST /papers/{id}/attempts；/tasks/{id}/attempts | 空 JSON 对象、Bearer、幂等键 |
| 草稿/提交 | PUT /attempts/{id}/answers；POST /attempts/{id}/submissions | Bearer、幂等键、version、answers:[{itemId,text}] |
| 自行批改 | POST /attempts/{id}/gradings | Bearer、幂等键、version、scores:[{itemId,score}]；score 为十进制字符串 |
| 记录/错题 | GET /attempts；/attempts/{id}；/mistakes；/mistakes/{id} | 仅本人，列表先限定本人再分页 |

用户名 ASCII 3–32 字符；密码 12–64 个 Unicode 字符且 UTF-8 不超过 72 字节；bcrypt 成本 12。登录失败不区分用户名不存在、口令错误和禁用。

## 账号管理（API02，任务 3.1）

POST /users 无 role 时保持公开学生注册契约；携带 role 时必须有有效管理员 Bearer，匿名不能指定角色，学生不能通过此分支提权。管理分支 role 只能 student/admin，学生 gradeId 必须存在，管理员 gradeId 必须为 null。创建、修改、禁用使用 Idempotency-Key；PUT 必须提供全部账号可编辑字段和预期 version，不能写 status、authVersion、passwordHash、createdAt 等内部字段。本人资料及密码修改仍走独立契约，不通过管理字段绕过。

GET /users 默认 page=1/pageSize=20、createdAt desc 同向 id；支持 sort=createdAt/username、direction=asc/desc，以及 role、status、gradeId、username 筛选。username 为不区分大小写的精确匹配，非法字段或不存在年级拒绝，不退化为全量查询。默认排除 deleted，显式 status=deleted 返回匿名墓碑；系统主体不在目录中，详情按不存在处理。管理员共享账号管理域，不人为隔离同级管理员。所有管理响应只含 id、username、role、status、gradeId、version、createdAt、updatedAt，无散列、认证版本、令牌或创建人内部字段。详情和写响应附 ETag，值为带引号版本。

新迁移 002_account_management 增加固定管理员守卫行。每次管理写事务先锁守卫，再验证并锁定操作者/目标账号、校验版本；移除 active admin 资格前检查剩余数量。最后一位启用管理员不能禁用、删除或降级，返回 BIZ_LAST_ADMIN，不留下账号/会话/审计修改。多个进程使用相同数据库守卫，不能用内存锁替代。升级 001+002 在同事务执行时，002 先完成系统主体的延迟外键检查再建 users 索引，随后恢复延迟检查模式；不改写 001。

角色变更、禁用和删除与 auth_version 增加、所有会话撤销、审计同事务。删除保留用户 UUID 和历史外键，替换用户名为随机 32 字符别名、清空年级、替换不可用密码散列，状态为 deleted；禁止普通恢复。重复 DELETE 返回原匿名墓碑，不重复改名/增加审计；未删除记录的旧 If-Match 返回版本冲突。此处完成账号身份和可见性删除，不代表任务 7.2 的文件、备份等副本清理已完成。

## 安全与幂等

访问令牌为 HMAC-SHA256 签名的 15 分钟凭证，客户端仅存内存。每次请求仍查服务端会话和用户认证版本；数据库不可用不放行。刷新 Cookie 为 __Host-exam_refresh，Path=/，无 Domain，HttpOnly/Secure/SameSite=Strict，会话族最多 7 日。服务端只存刷新凭证摘要；重放已消费令牌会持久化撤销全族。Cookie 写端点验 Origin，刷新与退出还验 CSRF。

登录幂等记录只存发行标识、时间和公开用户信息，凭证经服务端密钥重新派生，不存原始刷新令牌。原发行已消费、撤销或访问期已过时，要求新的登录键。刷新使用单次凭证状态机，禁止普通响应缓存重放。退出为自然幂等撤销；旧访问令牌随后不能再认证。

业务写接口按主体/方法/规范路径/键隔离，校验载荷摘要，24 小时内重放原结果；同键异载荷冲突。更新携带预期版本；永久唯一约束保护任务每人一次、成绩一次和错题汇总。草稿为增量覆盖；提交保存所传最终答案并保留先前已存的其他题答案。

安全端口定义于 domain/security.ts，由 Auth 实现并在组合根接入 authenticate、authorize、revokeUser。未登记路由、错误角色及缺少提供者时默认拒绝。管理员不能默认代阅卷；管理应用用例再次读取操作者资格，并在同一事务经仓储撤销端口处理角色变更、禁用和删除。

## 时间与计分

running → submitted → completed。窗口 start_at <= now < end_at，截止取时长和窗口结束的较早者。到期写请求持久化冻结但拒绝新答案；后台扫描同样幂等冻结。GET 不改状态、版本、审计和 Outbox，只返回有效 submitted 状态与 submittedAt=deadline，参考答案按有效状态开放，列表筛选与详情一致。返回实际存储版本；到期后直接批改在同一事务校验版本、冻结并确认成绩。

single 答案为选项文本；multiple 为去重选项文本数组的 JSON 字符串；boolean 为 true/false 文本；fill/short 为文本。每题最多 10000 字符，每卷最多 200 题，HTTP 体最多 128 KiB。未答可省略或用空文本；作答中不返回参考答案和解析。

每题 score 最多两位小数，0 到 maxScore，须为每题且仅评分一次；总分上限 999999.99。整数分单位精确累加，成绩、未满分错题、审计和 Outbox 同事务保存；完成后不能普通修改。成绩由学生自行确认，不宣称受监考成绩；同一原始题目只保留一条最近错误汇总。

## 查询与错误

未知字段拒绝，不自动删除或转换。分页默认 page=1/pageSize=20，pageSize 超过 100 截为 100，非正整数拒绝；白名单见 OpenAPI，同排序键追加同向 id。单次查询 items/total 使用同一快照；固定集跨页稳定，并发新增不承诺跨页快照。

| 业务码 | HTTP | 下一步 |
| --- | --- | --- |
| PARAM_INVALID | 400 | 按契约修正字段、类型、范围或大小 |
| AUTHN_REQUIRED、AUTHN_INVALID、AUTHN_SESSION_REVOKED | 401 | 登录或重新登录 |
| AUTHZ_FORBIDDEN | 403 | 使用授权账号和正式入口 |
| RESOURCE_NOT_FOUND | 404 | 返回列表；他人资源与不存在一致 |
| BIZ_IDEMPOTENCY_CONFLICT | 409 | 新内容用新键 |
| BIZ_VERSION_CONFLICT | 409 | 刷新后重试 |
| BIZ_STATE_CONFLICT、BIZ_UNAVAILABLE、BIZ_DEADLINE_REACHED | 409 | 查看状态或有效时段 |
| BIZ_USERNAME_EXISTS | 409 | 更换用户名 |
| BIZ_LAST_ADMIN | 409 | 先新增另一位启用管理员，再执行移除操作 |
| DEP_UNAVAILABLE | 503 | 稍后重试 |
| RATE_LIMITED | 429 | 按 Retry-After 等待 |
| INTERNAL_FAILURE | 500 | 凭 requestId 联系管理员 |

未登记异常映射安全 INTERNAL_FAILURE。/health/live、/health/ready 为公开运行端点；当前只验数据库连通，完整依赖就绪和运维按 operate-exam-platform 验收。

## 验证

`npm run gate --prefix backend` 执行严格类型、架构、OpenAPI 一致性和全部当前测试。测试启动随机本地端口的真实 PostgreSQL 临时实例，每例独立 schema 和合成数据，结束清理，不读取生产连接；安装依赖后无需外网，需允许本机进程和回环监听。接口用例包含真实 HTTP 客户端，不替代浏览器/HTTPS 证据。原演示执行根目录 `npm test`。完整 PowerShell 门禁、全平台追溯、性能容量、安全和人工评审按对应 change 验收。
