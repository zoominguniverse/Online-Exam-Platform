# 当前路由授权矩阵

前缀均为 /api/v1。每条入口显式登记；未登记默认拒绝。用户 A/B 是不同学生；管理员 A/B 共享账号管理域，但不默认拥有学生答卷权限。账号管理 API 已接入，附件及其他管理能力尚未实现，不能据此声称全平台 IA07 覆盖完成。

| 方法/路径 | 允许主体/归属 | 正常和拒绝验证 |
| --- | --- | --- |
| POST /users | 无 role：匿名注册学生；显式 role：仅认证管理员创建 student/admin | auth-exam/accounts：公开角色注入拒绝、学生管理分支拒绝、管理员创建两角色、重复用户名及非法年级拒绝；不响应散列 |
| GET /users | 管理员共享目录；默认隐藏系统及 deleted，显式筛选可见匿名墓碑 | accounts：学生/匿名拒绝，管理员分页/筛选、同键排序、禁止字段不输出 |
| GET /users/{id} | 管理员共享管理域；系统主体与不存在一致 | accounts：管理员 B 可查询 A 创建的账号；学生即使查自己也拒绝，系统主体拒绝 |
| PUT /users/{id} | 管理员，可编辑字段白名单、版本正确；已删除终态拒绝 | accounts：学生拒绝、未知字段/非法角色/年级拒绝、版本竞争和同键异载荷、角色变更立即撤销 |
| POST /users/{id}/suspensions | 管理员，目标启用、版本正确，保护最后启用管理员 | accounts：学生拒绝、并发自禁用最多一份成功、旧凭证/刷新下一请求拒绝、审计故障全部回滚 |
| DELETE /users/{id} | 管理员，If-Match 正确，保护最后启用管理员；重复删除幂等 | accounts：学生/缺条件/旧版本拒绝、并发自删除保护、匿名化/历史外键、审计故障无半份撤销 |
| POST /sessions | 匿名，正确凭据及允许 Origin | auth-exam：统一登录失败；幂等范围为已验证用户，不能读他人发行 |
| POST /sessions/refreshes | 对应 Cookie 会话族、Origin 与 CSRF | auth-exam：错误来源/CSRF拒绝、重放撤销族 |
| DELETE /sessions/{id} | 学生或管理员的当前会话 | auth-exam：本人退出即失效；跨会话 id 返回不存在，专用跨会话 HTTP 配对仍待联合安全回归 |
| POST /users/{id}/password-changes | 学生或管理员本人，当前密码 | auth-exam：改密全会话失效；应用先校验本人，完整管理生命周期待后续 |
| GET /papers | 学生，已发布非任务卷共享可用目录 | persistence/auth-exam：授权列表和不泄露答案；共享目录不捏造学生间隔离 |
| GET /papers/{id} | 学生，已发布非任务卷 | auth-exam：试卷内容无参考答案；归档/不存在拒绝 |
| POST /papers/{id}/attempts | 学生，已发布且时段有效 | persistence：固定/时段重练、左右边界；任务卷不能绕过资格直接开始 |
| POST /tasks/{id}/attempts | 学生，在发布时冻结收件集合内 | persistence：目标学生并发恢复同一答卷，另一学生拒绝 |
| GET /attempts | 学生，仅 owner_id=本人 | persistence：另一学生列表为空；先限定归属再分页 |
| GET /attempts/{id} | 学生，fetch-then-check 本人 | persistence/auth-exam：A/B 替换 ID 返回不存在，不泄露答案 |
| PUT /attempts/{id}/answers | 学生，本人且未截止/未提交/版本正确 | persistence/auth-exam：他人拒绝、旧版本冲突、到期草稿冻结而新载荷拒绝 |
| POST /attempts/{id}/submissions | 学生，本人及合法状态 | persistence/auth-exam：他人拒绝、重试无重复提交/事件 |
| POST /attempts/{id}/gradings | 学生，本人待批改 | persistence：他人拒绝、越界评分拒绝、并发确认一次 |
| GET /mistakes | 学生，仅本人 | auth-exam：本人错题及得分；数据库查询以 owner_id 前置限定 |
| GET /mistakes/{id} | 学生，fetch-then-check 本人 | persistence：另一学生按不存在拒绝 |

受保护入口先调用 Security.authenticate/authorize；POST /users 的管理分支也显式执行两步。管理用例再次检查操作者当前角色/状态；账号管理共享域不捏造同级隔离，系统主体和机密字段始终不开放。最后管理员检查在数据库守卫锁内，不能只在页面隐藏按钮。具体业务配对见 docs/testing/traceability.md；完整安全矩阵、浏览器存储/双标签、附件和发布演练仍待 secure-exam-platform 联合验收。
