# 数据字典

PostgreSQL 17 中业务标识为 UUID，时间使用 `timestamptz`。Flyway 由迁移账号执行，业务容器使用 `exam_app`。

| 表 | 字段及约束 | 归属与生命周期 |
| --- | --- | --- |
| `grades` | `id`、`name`、`created_at`、`enabled` | 公开目录仅返回启用项的标识和名称 |
| `users` | `id`、`username`、唯一 `username_normalized`、`password_hash`、`role`、`status`、`grade_id`、`version`、创建和更新时间 | 用户名经修剪、NFKC 规范化及小写比较。密码使用 BCrypt 12。启用或禁用的学生必须关联年级，管理员没有年级。软删除保留 ID，清除密码及年级，并将用户名替换成匿名标识 |
| `web_sessions` | 主键 `token_hash`、`user_id`、`last_seen_at`、`expires_at` | 令牌只存 SHA-256 摘要。空闲 30 分钟或满 12 小时后失效。Web 退出撤销当前会话 |
| `identity_guard` | 固定主键 `id=1` | 账号管理事务对该行加锁，串行核对最后一个启用管理员 |
| `login_limits` | 主键 `bucket`、`window_start`、`failures` | 按哈希后的账号与来源 IP 分别计数，5 分钟内达到 5 次失败后暂停尝试 |
| `wechat_bindings` | `id`、唯一 `user_id`、联合唯一 `app_id, open_id` | 仅启用学生可绑定。解绑、改为管理员或删除账号时删除关联 |
| `wechat_tokens` | 主键 `token_hash`、`binding_id`、`expires_at` | 令牌只存 SHA-256 摘要，12 小时失效。解绑删除全部关联令牌。禁用和改密撤销令牌 |
| `wechat_used_codes` | 主键 `code_hash`、`expires_at` | 存储 AppID 与一次性登录码的 SHA-256 摘要。成功交换后记录 10 分钟，后续交换时清理过期记录 |

账号禁用、删除或改密时，账号变更和会话撤销在同一事务执行。角色变更撤销 Web 会话并移除已有微信绑定。

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
