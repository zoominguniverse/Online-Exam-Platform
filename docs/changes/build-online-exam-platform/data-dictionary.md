# 数据字典

本轮补齐Web路由和验收，不新增字段、表或迁移；V1–V13保留。直达编辑使用原业务UUID和expectedVersion，群发去重仍由message_recipients唯一约束保证，已读时间仅首次写入；本轮新增两个独立Web会话的实际验证。

PostgreSQL 17 中业务标识为 UUID，时间使用 `timestamptz`。Flyway 由迁移账号执行，业务容器使用 `exam_app`。

| 表 | 字段及约束 | 归属与生命周期 |
| --- | --- | --- |
| `grades` | `id`、`name`、`created_at`、`enabled` | 公开目录仅返回启用项的标识和名称 |
| `users` | `id`、`username`、唯一 `username_normalized`、`password_hash`、`role`、`status`、`grade_id`、`version`、创建和更新时间 | 用户名经修剪、NFKC 规范化及小写比较。密码使用 BCrypt 12。启用或禁用的学生必须关联年级，管理员没有年级。软删除保留 ID，清除密码及年级，并将用户名替换成匿名标识 |
| `web_sessions` | 主键 `token_hash`、`user_id`、`last_seen_at`、`expires_at` | 令牌只存 SHA-256 摘要。空闲 30 分钟或满 12 小时后失效。Web 退出撤销当前会话 |
| `identity_guard` | 固定主键 `id=1` | 账号管理事务对该行加锁，串行核对最后一个启用管理员 |
| `login_limits` | 主键 `bucket`、`window_start`、`failures` | 按哈希后的账号与来源 IP 分别计数，5 分钟内达到 5 次失败后暂停尝试 |
| `wechat_bindings` | `id`、唯一 `user_id`、联合唯一 `app_id, open_id` | 历史迁移兼容表；当前不创建或认证这些凭据，仅用于账号生命周期及恢复清理 |
| `wechat_tokens` | 主键 `token_hash`、`binding_id`、`expires_at` | 历史迁移兼容表；当前不创建或认证这些凭据，仅用于账号生命周期及恢复清理 |
| `wechat_used_codes` | 主键 `code_hash`、`expires_at` | 历史迁移兼容表；当前不创建或认证这些凭据，仅用于账号生命周期及恢复清理 |

账号禁用、删除或改密时，账号变更和会话撤销在同一事务执行。角色变更撤销 Web 会话并移除已有历史绑定。

## 历史迁移兼容说明

V3创建的 `wechat_bindings`、`wechat_tokens`、`wechat_used_codes` 是保留的历史表，不属于当前业务认证或验收能力。当前版本不再交换外部身份、创建绑定或签发这些令牌。下表及上文的旧身份描述仅反映历史迁移结构；当前仅保留生命周期清理和恢复时清空逻辑。V1–V13均不编辑、不回滚。

## 题库、试卷和年级任务

| 表 | 约束和归属 |
| --- | --- |
| `subjects` | `grade_id` 指向年级。规范化名称在年级内唯一，被内容引用时限制删除 |
| `questions`、`question_versions` | 题目主记录指向当前版本，版本由 `question_id,version` 标识。版本含学科、题型、难度、百分之一分整数、净化内容和结构化答案。归档保留旧版本 |
| `attachments`、`attachment_links` | 私有存储 UUID、上传者、实际类型、大小和摘要。按题目版本及题干、解析用途关联，已引用图片继续保留 |
| `papers`、`paper_versions` | 主记录独立指向当前发布和草稿版本，`version` 用于乐观并发控制。发布版本包含名称、时长和开放区间，发布后保留 |
| `paper_items` | 卷版本内题目及位置唯一，保存组名、组序、题序、题目版本和分值 |
| `tasks` | 年级、名称、草稿卷列表、状态、并发版本和发布时间 |
| `task_papers`、`task_recipients` | 冻结卷版本及学生 UUID。卷标识跨任务唯一，归档继续保留卷和资格快照 |
| `attempts` | 答卷 UUID、本人、试卷及发布版本、开始与截止时刻、状态和版本；`task_id IS NOT NULL` 时 `(owner_id,paper_id)` 部分唯一索引确保每名学生每张任务卷一次，普通卷不受约束。V9 还以 `(task_id,paper_id,paper_version_id)` 外键固定任务发布时的卷版本，以 `(task_id,owner_id)` 外键固定发布时成员资格 |
| `attempt_items` | 按答卷与题序冻结题目版本、题干、选项、参考答案、解析和原分值。`(attempt_id,position)` 与 `(attempt_id,question_id)` 唯一；作答 DTO 仅输出安全字段，不包含参考答案与解析 |
| `attempt_answers` | V10 新增；`(attempt_id,question_id)` 主键并外键到答卷快照，`answer` 为按题型校验后的 JSONB。每次全量保存先整体校验，再同一事务替换已有答案并递增 `attempts.version`；遗漏或显式 null 清空，不保存空条目 |
| `attempt_start_keys` | `(owner_id,key)` 唯一，记录普通卷或任务卷开始请求对应的答卷和 24 小时有效期；同键换卷冲突，过期后可重用。任务卷不同键仍由答卷部分唯一索引保证只存在一份 |
| `attempts`（V11 扩展） | `frozen_at` 为实际持久化冻结时间，`freeze_reason` 为 `MANUAL/DEADLINE`，`automatic_score_minor` 为客观总分；`IN_PROGRESS` 时三者必须为空，冻结状态必须非空。纯客观卷写 `final_score_minor`，混合卷保留 null；冻结仅递增一次版本，原截止时间不变。到期扫描使用部分索引 `(deadline_at,id)` |
| `attempt_results` | V11；每答卷每快照题唯一，复合外键指向 `attempt_items`。`score_minor` 为已确定的客观整数分，主观待批改为 null。答案、逐题结果和冻结状态同一事务提交，失败全部回滚 |
| `attempt_submit_keys` | V11；`(owner_id,key)` 唯一，与开始键分表隔离。记录答卷、规范化 JSON 的 SHA-256 及 24 小时有效期，不存原始请求正文。对象字段顺序规范化、数组顺序保留；同键异载荷/答卷冲突，过期后仍由答卷冻结状态防止重复效果 |
| `mistakes` | V11；`(owner_id,question_id)` 主键，关联最近错误答卷及题目版本、`last_error_at`。纯客观卷最终完成时与分数同事务 upsert，重复冻结不重复写，后来答对不删除既有错误；混合卷在自行批改最终确认时与分数同事务更新；本人错题 API/Web 使用最近错误快照 |

上轮新增 V11；本轮新增 V12、V13，V1–V11 不变。业务账号仅获得新表所需的读写权限，仍不具备 DDL。HTTP 与截止 worker 统一先锁账号再锁答卷，避免错题外键检查与并发提交产生反向锁等待；worker 使用 `SKIP LOCKED`。

## V12 / V13 学习记录与沟通

| 表 | 字段、约束及权限 |
| --- | --- |
| gradings | attempt_id 主键/外键、owner_id、source=SELF、confirmed_at、final_score_minor；业务账号 SELECT/INSERT，不允许改写确认 |
| operation_keys | (actor_id,operation,key) 主键，target_id、request_hash、expires_at；SELF_GRADE/SEND_MESSAGE 分离；只存规范 JSON 摘要，24 小时；由主体账号行锁串行，无需扩大 UPDATE 权限 |
| profiles | user_id 主键、name(100)、phone(32)、birthday、avatar_id 外键、version>0；创建时间读取 users.created_at，账号删除匿名化资料 |
| messages | UUID、sender_id、title(200)、body、sent_at；只追加，不允许更新正文 |
| message_recipients | (message_id,user_id) 主键及外键，read_at 首次赋值后不变；人数由行计数，收件人整体事务 |
| audit_events | UUID、request_id、actor_id、module/action、target_id、result、created_at；按主体时间和请求索引；业务仅追加，无密码、令牌或答案正文 |
| user_daily_activity | (user_id,day) 主键；香港自然日成功认证请求去重；成功登录也入统计 |
| deleted_account_ledger | user_id 主键无外键、deleted_at；独立保留删除事实，恢复重放。V13 回填已有删除用户，状态转 DELETED 触发插入清单及资料匿名化，兼容回退旧应用 |

自行批改完整事务：校验主体及快照分数 → 更新主观逐题结果 → INSERT 唯一 grading → attempts 置 COMPLETED/总分/version+1 → upsert 错题 → 写幂等记录及审计。任何步骤失败全部回滚；任务进度查询直接投影已提交状态，无异步计数漂移。金额/分数始终为百分之一分整数。

头像清理排除 profiles 当前引用；历史题目附件由不可变题目版本/答卷引用保护。所有新表按功能最小授权，exam_app 仍无 schema CREATE。恢复先重放最新删除清单，再撤销全部 Web/微信令牌，历史 users UUID 和答卷关系保持。
