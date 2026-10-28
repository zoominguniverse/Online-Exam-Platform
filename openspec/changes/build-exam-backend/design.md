# Design

## Context

见 proposal.md。收尾检查发现根目录新增免依赖 Node 静态服务 `server.cjs` 和本地考试演示，`package.json` 仅包含演示启动与测试命令；尚无业务 API、数据库或迁移，业务服务仍从零建立。前后端分离与 PostgreSQL 是用户明确约束；产品行为取自 design-exam-product，安全服务由 secure-exam-platform 负责。数据与 API 规格是实施契约，不以演示或框架默认行为替代。

## Goals / Non-Goals

**Goals:** 以单个模块化后端和独立工作进程实现业务，所有存储约束、资源归属及流程变更可测试，前端仅依赖 OpenAPI 契约。

**Non-Goals:** 不拆微服务，不引入多数据库权威来源，不提供服务端页面渲染，不实现 AI 阅卷、文件作答、业务导入导出或生产前端。

## Decisions

### 1. 模块化单体和依赖边界

拟采用 TypeScript、Node.js、Fastify、边界校验库和 PostgreSQL 驱动，精确版本在首个实施任务中按许可与漏洞检查后锁定。选择小型 HTTP 框架和显式数据访问以便检查 SQL、事务及依赖；相比微服务减少分布式事务和部署负担，相比任意 ORM 自动级联更容易审计删除语义。

计划布局：`backend/src/interfaces` 承载路由、DTO、响应；`application` 承载用例与事务边界；`domain` 承载规则与仓储端口；`infrastructure` 实现 PostgreSQL、私有存储、时钟、Outbox。逻辑流为接口 → 业务 → 数据访问端口，组合根注入实现，业务层不导入驱动或文件客户端。架构门禁检查导入边和 SQL 位置。

前后端分别交付，OpenAPI 放 `docs/api/openapi.yaml`，契约驱动客户端与接口测试。网关同源转发 `/api/`，同源部署便利 Cookie 安全，不改变前后端独立部署事实。

### 2. 资源路由与职责分配

下表中的用户、题目等均须展开到 OpenAPI 的方法、输入、输出、授权、幂等与错误清单；不是允许跳过文档的占位符。

| 能力 | API 资源族／关键动作 | 实现归属 |
| --- | --- | --- |
| 注册与用户管理 | `/api/v1/users`，`/users/{id}`，`/users/{id}/suspensions` | 后端账号 CRUD；安全负责角色校验与会话撤销 |
| 登录、刷新、退出、改密 | `/api/v1/sessions`，`/sessions/refreshes`，`/sessions/{id}`，`/users/{id}/password-changes` | 安全 change；本 change 集成服务端口 |
| 资料、头像、活动、管理员时间线 | `/users/{id}/profiles`，`/users/{id}/avatars`，`/users/{id}/activities`，`/users/{id}/timelines` | 后端业务与归属，安全提供校验 |
| 学科与题目 | `/subjects`、`/questions`、`/questions/{id}/versions`、`/questions/{id}/attachments/{attachmentId}`（均在 `/api/v1` 下） | 管理员 CRUD 和安全读取 |
| 试卷与任务 | `/papers`、`/tasks`、`/tasks/{id}/publications`、`/papers/{id}/publications`、`/tasks/{id}/attempts`、`/papers/{id}/attempts` | 版本、发布和开始作答 |
| 答卷 | `/attempts`、`/attempts/{id}`、`/attempts/{id}/answers`、`/attempts/{id}/submissions`、`/attempts/{id}/gradings` | 草稿保存、提交与自行批改 |
| 错题 | `/mistakes`、`/mistakes/{id}` | 个人错题查询 |
| 消息 | `/messages`、`/messages/{id}`、`/messages/{id}/publications`、`/message-receipts`、`/message-receipts/{id}/read-receipts` | 管理员草稿发送；收件人查询与已读 |
| 统计与日志 | `/statistics`、`/audit-events` | 仅管理员只读 |

子资源单例更新使用 PUT，自然幂等删除使用 DELETE；发布、提交等创建动作使用 POST 和幂等键。禁止客户端 PATCH status。只开放业务有定义的组合，不把路由表理解成每资源都支持全部 HTTP 方法。

### 3. PostgreSQL 模型和历史保护

| 表族 | 核心关系与约束 | 删除行为 |
| --- | --- | --- |
| users、grades、user_profiles | users.username_normalized 唯一；role 和状态检查；grade_id 外键；profile.user_id 唯一 | 有历史的账号匿名化保留标识，撤销身份；年级被引用 RESTRICT |
| subjects、questions、question_versions | 题目学科外键；版本号在同题唯一；题干受约束 JSONB、参考答案独立授权字段 | 已用版本不可原地改，引用 RESTRICT，主记录归档 |
| papers、paper_versions、paper_items | 试卷类型枚举；item 引用 question_version；同版本位置唯一；分数 numeric(8,2) | 有答卷引用 RESTRICT，未发布草稿子项可 CASCADE |
| tasks、task_recipients | 目标年级、已冻结 paper_version；任务学生组合唯一 | 发布后保留资格，归档不删答卷 |
| attempts、attempt_answers、gradings | attempts.owner_id；task_id 非空时 `(task_id,owner_id)` 唯一；每答卷每题一答案；grading.attempt_id 唯一 | 记录留存到期由受控清理处理 |
| mistakes | `(owner_id,question_id)` 唯一；last_attempt_id 和题目版本快照 | 账号删除／留存到期清理 |
| messages、message_receipts | 发布人；`(message_id,recipient_id)` 唯一；read_at 首次赋值 | 已发送不可改，留存到期清理 |
| attachments、attachment_links | 对象键、上传者、内容摘要；业务关联外键和授权归属 | 私有文件通过删除事件清理，禁止自由多态字符串绕过外键 |
| sessions、refresh_tokens | 会话族、认证版本、令牌摘要 | 撤销标记优先，安全 change 管理 |
| idempotency_records、outbox_events | 幂等范围组合唯一；事件 id 唯一；载荷摘要、租约及重试状态 | 依生命周期清理 |
| audit_events、user_activities、deletion_jobs | 系统／用户主体、request_id、脱敏摘要；删除清单状态 | 审计只追加，按本期拟议产品留存基线到期处理 |

所有业务表统一 UUID、created_at、updated_at、created_by，时间使用 timestamptz 并在连接会话设置 UTC。注册事务先分配用户 id，以延迟外键或同事务插入满足自引用创建人；系统主体不允许登录。状态通过 TEXT 加 CHECK 与字典枚举表示，避免数据库枚举类型回滚不便。归属索引以 owner_id/recipient_id 开头，列表排序索引追加 id。

数据字典唯一源为 `docs/data/dictionary.md`；敏感字段表引用安全 change 的 `docs/security/data-classification.md`，不复制两套脱敏规则。README 未给留存期，DT06 的数字为本次拟议产品基线，不声称法律要求。

### 4. 事务和幂等边界

- 开始任务：检查已冻结资格，事务内按任务学生唯一约束创建或返回已有答卷；跨年级访问在写入前拒绝。
- 保存／提交：先校验 DTO，再在事务内校验所有者、时钟、预期版本；条件更新成功才写答案及提交审计。已截止时冻结现有草稿，拒绝新载荷；后台扫描与读取路径使用同一幂等冻结逻辑。
- 批改确认：校验每题评分范围并用精确小数求和，同一事务更新答卷完成态、写 grading、upsert 错题及审计。不在事务内渲染题干或访问文件。
- 发消息：事务内检查所有收件人并去重，写不可变发布记录、收件人和 Outbox；任一非法收件人整批拒绝。站内可见性以数据库提交为准，Outbox 只承载派生通知／活动等副作用。
- 通用幂等：按主体、方法、规范路径、键唯一保存请求摘要和响应结果 24 小时。业务永久唯一约束仍保护任务一次性；超过缓存期不会重复确认成绩或消息发布。同键异载荷返回冲突。
- 更新使用 version 条件；最后管理员操作锁定管理员守卫记录并在同事务检查，避免两个管理员同时禁用后剩零人。

Outbox 由业务事务写，工作进程在短事务中领取带租约事件，提交领取后执行外部调用，再确认；重试采用 1／2／4／8／16 秒退避，最多 5 次，超限失败队列并告警。消费者以事件 id 去重，租约过期可重领，不承诺跨系统“恰好一次”。私有存储自身重试不得突破总事件预算。

### 5. 查询、校验和配置

page/pageSize 与错误封装依 CT03、CT06；例如试卷列表仅允许 type、subjectId、gradeId、availability 筛选，createdAt、title 排序并追加 id；每资源在 OpenAPI 注册独立白名单。固定数据下保证稳定分页，并发新增时页码分页不承诺快照隔离，若需要一致导出须另设快照机制。

建议统一边界：用户名 3–32 个允许字符并做规范化唯一比较，姓名 1–64、答案每题最多 10000 字符、题干序列化最多 100 KiB、每卷最多 200 题；题型特有约束在契约登记。有效年级来自 grades 目录，不把任意数字当合法年级。

必需环境键分组：`DATABASE_URL`、`APP_ORIGIN`、`CORS_ALLOWED_ORIGINS`、`PRIVATE_STORAGE_ROOT` 或固定存储适配器所需键、`OUTBOX_ENABLED`、安全会话键、日志与限流键。启动时整体校验，配置样例仅列键。公开运行参数和秘密分别注入；框架默认值不能掩盖未提供的项目必需配置。

## Risks / Trade-offs

- [后端与安全互相需要接口] → 先冻结安全端口和资源清单，安全模块接入后才进行对外业务验收，默认拒绝不被临时关闭。
- [题目历史版本增加存储] → 保留引用版本直至关联记录留存到期，以正确历史优先。
- [分页并发变动] → 声明固定集保证，测试不把实时新增误认成静态排序问题。
- [长期事务与文件残留] → Outbox 和暂存区清理，事务内禁止外部调用并做故障注入。

## Migration Plan

独立建立 backend，保留根目录演示和现有脚本，前端 localStorage 演示成绩不导入为可信正式答卷。先建立版本化基线迁移和系统主体，再按账号／题库／答卷／消息依次增加表。每次迁移只增文件，声明升级、条件重跑、回退及数据恢复条件，在真实 PostgreSQL 副本演练；运行时角色不拥有改结构权限。发布遵循 RD05，收缩在 7 日回滚窗口后独立执行。失败回退兼容应用版本；破坏性迁移须恢复备份，不假定逆 SQL 可找回丢失的数据。
