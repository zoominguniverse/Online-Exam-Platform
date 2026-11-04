# PostgreSQL 数据字典

当前对应迁移 001_foundation 和 002_account_management。迁移为唯一结构实现，本文件为唯一字段含义说明。分类和脱敏引用 [数据分类](../security/data-classification.md)，不另行定义规则。

## 通用字段与约束

除 schema_migrations 技术表外，所有表都有：id UUID 主键（无默认值，由服务生成）；created_at、updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP；created_by UUID NOT NULL 外键 users.id，ON DELETE RESTRICT。下列字段清单省略这四个共同字段。连接会话时区 UTC；时间输出 ISO 8601。分数 numeric(8,2)，业务采用整数分单位计算再转换字符串，禁止浮点累加。

users.created_by 自引用为 DEFERRABLE INITIALLY DEFERRED，允许同事务注册本人及创建系统主体。其他外键默认立即检查；所有历史引用 RESTRICT，不提供级联删除。系统主体固定 UUID 00000000-0000-4000-8000-000000000001，role=system，无口令，不允许登录。

## 表和字段

| 表 | 专有字段（类型；默认／约束；含义） |
| --- | --- |
| users | username text NOT NULL：展示用户名；username_normalized text NOT NULL UNIQUE：小写唯一名；password_hash text：bcrypt 散列，非 system 必填；role text NOT NULL：student/admin/system；status text NOT NULL DEFAULT active：active/disabled/deleted；grade_id UUID 可空 FK grades：学生年级；auth_version integer NOT NULL DEFAULT 1 >0：凭据撤销版本；version integer NOT NULL DEFAULT 1 >0：并发编辑版本 |
| account_guards | 无专有字段；固定 id=00000000-0000-4000-8000-000000000002，含通用审计字段；账号管理写事务先 SELECT FOR UPDATE 锁住此行，再锁操作者和目标账号并检查有效管理员数量；运行角色需要此表 SELECT/UPDATE 权限 |
| grades | title text NOT NULL UNIQUE：年级目录名称 |
| sessions | owner_id UUID NOT NULL FK users：会话本人；auth_version integer NOT NULL：创建时认证版本；csrf_hash text NOT NULL：CSRF 校验摘要；expires_at timestamptz NOT NULL：会话到期；revoked_at timestamptz 可空：撤销时间 |
| refresh_tokens | session_id UUID NOT NULL FK sessions：所属会话族；token_hash text NOT NULL UNIQUE：刷新令牌摘要；consumed_at timestamptz 可空：首次消费时间；expires_at timestamptz NOT NULL：令牌到期 |
| subjects | title text NOT NULL：学科名；status text NOT NULL DEFAULT active：active/archived |
| questions | subject_id UUID NOT NULL FK subjects：所属学科；status text NOT NULL DEFAULT active：active/archived |
| question_versions | question_id UUID NOT NULL FK questions：原始题目；version integer NOT NULL >0：版本；content JSONB NOT NULL：题型、题干、选项、参考答案及解析；UNIQUE(question_id,version) |
| papers | title text NOT NULL：试卷标题；type text NOT NULL：fixed/timed/task；status text NOT NULL：draft/published/archived；duration_seconds integer NOT NULL 1–86400：作答时长；start_at/end_at timestamptz 可空：有效窗口，timed 必填且 start_at<end_at；current_version integer NOT NULL DEFAULT 1 >0：当前版本号 |
| paper_versions | paper_id UUID NOT NULL FK papers：原始试卷；version integer NOT NULL >0：版本；snapshot JSONB NOT NULL：不可变题干与参考信息、题目版本、分值和时长；UNIQUE(paper_id,version)，UNIQUE(id,paper_id) |
| paper_items | paper_version_id UUID NOT NULL FK paper_versions：所属卷版本；question_version_id UUID NOT NULL FK question_versions：引用题目版本；position integer NOT NULL >0：题序；max_score numeric(8,2) NOT NULL >=0：满分；UNIQUE(paper_version_id,position)，UNIQUE(paper_version_id,id) |
| tasks | paper_id UUID NOT NULL FK papers：原试卷；paper_version_id UUID NOT NULL：冻结卷版本，与 paper_id 组成 FK paper_versions(id,paper_id)；grade_id UUID NOT NULL FK grades：目标年级；status text NOT NULL：draft/published/archived；start_at/end_at timestamptz NOT NULL 且 start_at<end_at：有效窗口 |
| task_recipients | task_id UUID NOT NULL FK tasks：任务；owner_id UUID NOT NULL FK users：发布时冻结的目标学生；UNIQUE(task_id,owner_id) |
| attempts | owner_id UUID NOT NULL FK users：答卷本人；paper_version_id UUID NOT NULL FK paper_versions：历史卷版本；task_id UUID 可空 FK tasks：任务或普通练习；status text NOT NULL DEFAULT running：running/submitted/completed；version integer NOT NULL DEFAULT 1 >0：写入版本；snapshot JSONB NOT NULL：作答时历史快照；started_at/deadline timestamptz NOT NULL 且 deadline>started_at：开始和截止；submitted_at timestamptz 可空：手动提交或截止时刻；total numeric(8,2) 可空 >=0：确认总分；UNIQUE(task_id,owner_id)，UNIQUE(id,paper_version_id) |
| attempt_answers | attempt_id UUID NOT NULL：所属答卷；paper_version_id UUID NOT NULL：所属卷版本，与 attempt_id 组成 FK attempts；item_id UUID NOT NULL：题项，与 paper_version_id 组成 FK paper_items；answer text NOT NULL 长度<=10000：文本答案；UNIQUE(attempt_id,item_id) |
| gradings | attempt_id UUID NOT NULL UNIQUE FK attempts：唯一确认成绩；scores JSONB NOT NULL：逐题十进制字符串分值；total numeric(8,2) NOT NULL >=0：精确总分 |
| mistakes | owner_id UUID NOT NULL FK users：本人；question_id UUID NOT NULL FK questions：原始题目；last_attempt_id UUID NOT NULL FK attempts：最近错误答卷；snapshot JSONB NOT NULL：当时题目、答案和得分；UNIQUE(owner_id,question_id) |
| idempotency_records | scope text NOT NULL：主体/方法/规范路径；key text NOT NULL：客户端幂等键；digest text NOT NULL：请求摘要；result JSONB NOT NULL：原响应业务数据；expires_at timestamptz NOT NULL：24 小时重放期限；UNIQUE(scope,key) |
| outbox_events | action text NOT NULL：事件类型；object_id UUID NOT NULL：事件对象标识，仅由业务事务写入；payload JSONB NOT NULL：最小派生载荷；status text NOT NULL DEFAULT pending：pending/leased/completed/failed；attempts integer NOT NULL DEFAULT 0，0–5：已安排重试次数；available_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP：下次执行；lease_until timestamptz 可空：租约截止；lease_token UUID 可空：领取凭证；last_error text 可空：安全失败分类；UNIQUE(action,object_id) |
| event_receipts | event_id UUID NOT NULL UNIQUE FK outbox_events：内部数据库消费者去重凭据，与内部效果同事务；外部消费者仍须按事件 ID 去重 |
| audit_events | action text NOT NULL：状态变更动作；object_id UUID NOT NULL：业务对象；request_id text NOT NULL：请求关联；触发器拒绝 UPDATE/DELETE，禁止普通变更覆盖历史 |

schema_migrations 是迁移技术表：version text 主键（迁移文件名），digest text NOT NULL（升级与回退内容的组合 SHA-256），applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP。它不属于用户业务记录，不使用业务审计列。

答卷状态约束：running 要求 submitted_at、total 均空；submitted 要求 submitted_at 非空、total 空；completed 两者均非空。后台未处理的到期 running 行可在只读响应中呈现 submitted；数据库版本不因此变化。

## 索引

所有主键及 UNIQUE 自动创建唯一索引；附加索引：sessions(owner_id,id)、papers(created_at,id)、papers(title,id)、attempts(owner_id,created_at,id)、mistakes(owner_id,created_at,id)。attempts(deadline,id) 使用 status=running 部分索引供后台扫描。列表按白名单列和同向 id 稳定排序；固定集稳定，并发新增不承诺跨页快照。

002 新增 users_directory_idx(role,status,created_at,id)、users_grade_id_idx(grade_id,created_at,id)、users_username_idx(username_normalized,id)。账号管理列表默认隐藏 deleted 和 system；显式 status=deleted 可查询匿名化墓碑。username 筛选使用小写精确匹配，非任意 SQL/LIKE；所有列表字段来自白名单。

账号管理 PUT 每次增加 version；变更角色、禁用、删除在同事务增加 auth_version 并撤销所有 sessions。删除保留 UUID 和历史外键，username 改为随机 deleted_ 前缀别名（32 字符）、grade_id 清空、密码换成不可知随机密码的 bcrypt 散列，status=deleted。普通管理操作禁止恢复已删除账号；跨存储及长期留存清理属于任务 7.2，不将此处的身份撤销当作所有副本已清理。

## 迁移纪律与权限

迁移执行在事务内获取按 schema 隔离的 advisory lock；先核验全部已执行迁移的升级及回退摘要，再应用新增版本。只增迁移文件；不修改历史记录绕过摘要校验。运行时数据库角色只授予必要 SELECT/INSERT/UPDATE，audit_events 仅 SELECT/INSERT，不授予 DDL、TRUNCATE 或 schema_migrations 写权限。开发隔离测试使用拥有 schema 的测试角色，不代表生产权限。

001 的回退仅用于空库／可丢弃测试副本，会移除全部基础数据，不可用于已有业务数据的无损回退。生产新增迁移遵循扩展后收缩；结构收缩必须过 7 日回滚窗口并备份。业务数据恢复必须走备份恢复流程。

002 是新增守卫表及索引的扩展，升级不改写既有用户；回退仅删除新表/索引，保留账号、版本、会话和审计，旧应用读取继续有效。回退结构后不能继续运行依赖守卫的新管理入口，缺少守卫时默认返回 DEP_UNAVAILABLE。
