# 当前后端基础场景追溯

来源为 openspec/changes/build-exam-backend/specs 下 api-contracts、exam-api、exam-data 及 secure-exam-platform/specs/identity-access。这里只索引当前已交付组件；未列出的全平台场景尚未验收，不能将此表当作 verify-exam-platform 的完整覆盖。

测试前缀均为 backend/tests/。用例名含来源编号，可从用例反查下表；同一组正常流程中的拒绝/边界断言与专用 failure/boundary 用例共同验证副作用。真实 HTTP 用例通过回环 TCP 调用，其他接口/存储用例在真实 PostgreSQL 上调用适配器和用例服务。

| 来源/场景 | 用例文件与测试名定位 | 层次与关键断言 |
| --- | --- | --- |
| CT01-F | unit/failure/architecture.test.ts，CT01-F | 单元/工具，反向导入、驱动直连、业务 SQL 拒绝 |
| CT02-N、CT03-N/F、CT04-F | interface/failure/contracts.test.ts，CT03-N/F | 接口，统一业务码/HTTP、字段拒绝先于业务调用、无堆栈 |
| CT07-F | unit/failure/config.test.ts，CT07-F | 单元/进程，逐键缺失和非法值非零退出，值不泄露 |
| CT06-N/F | interface/boundary/pagination.test.ts；interface/normal/persistence.test.ts，CT06-N/F | 接口/真实数据库，相同排序键分页不重不漏、超限截断、非法字段拒绝 |
| CT05-N/F | interface/normal/persistence.test.ts，API04/CT05；normal/auth-exam.test.ts，API01/API05-07 | 真实数据库/HTTP，同键并发只提交一次、异载荷冲突、旧版本拒绝、UUID 大小写使用相同规范路径 |
| DT01-N、DT03-N、DT05-N/F | interface/normal/persistence.test.ts，DT01/DT03/DT05 | 真实数据库，逐字段字典核对、UTC、系统主体、迁移重跑、历史变更拒绝、回退再升级 |
| API04-N/F | interface/normal/persistence.test.ts，API04/CT05、API04/05 | 真实数据库，8 个并发开始只有一份任务答卷，非目标拒绝，固定可重练、时段左右边界 |
| API05-N/F、API05-N2、CT02-N2 | interface/normal/persistence.test.ts，API05-N2/CT02-N2、API04/05 | 真实数据库/接口，截止前草稿保留，过期新载荷拒绝，GET 前后状态/版本/审计/事件均不改，扫描只冻结一次，到期读取后可直接批改 |
| API06-N/F、DT04-F | interface/normal/persistence.test.ts，API06/DT04、API06-F | 真实数据库，精确 60.30 总分、0/满分两端及越界、并发确认一次、错题汇总、审计注入失败时成绩/错题/事件一起回滚 |
| API07-N/F、IA02-N/F | interface/normal/persistence.test.ts，API07/IA02、API06-F；normal/auth-exam.test.ts，IA02/IA05 | 真实数据库/HTTP，他人资源按不存在、本人列表隔离、未提交无答案、归档后历史可读 |
| DT04-N/F | interface/failure/outbox.test.ts，DT04-N/F、DT04-N | 真实数据库，回滚无事件，投递后未确认的租约重领，旧领取凭证无效，消费效果不重复，五档退避到失败队列 |
| IA01-F1/F2 | interface/failure/contracts.test.ts，IA01-F1/F2 | 接口，未登记、匿名、错误角色均无业务调用 |
| IA03-N/F | interface/normal/auth-exam.test.ts，IA03/CT04 | 真实 HTTP/数据库，相同密码不同 bcrypt 盐、成本 12、12–64 字符和 72 字节边界、角色注入拒绝、统一失败文案 |
| IA04-F、IA05-N/F、IA06-F | interface/normal/auth-exam.test.ts，IA04/IA06、IA02/IA05 | 真实 HTTP/数据库，刷新摘要单次轮换、重放全族撤销、改密/退出/撤销端口立即失效、依赖不可用默认拒绝、Cookie 属性和 Origin/CSRF 拒绝 |
| API01-N（答卷子链路） | interface/normal/auth-exam.test.ts，API01/API05-07 | 真实 HTTP，注册→登录→试卷→五题型答案→提交→自行批改→历史/错题；不代表题库创编/任务发布/消息全链路完成 |
| API02-N/F、IA01-F2、IA05-N、CT04/05/06、DT04-F（账号管理） | interface/normal/accounts.test.ts，七组 API02/CT06/DT04 用例 | 真实 HTTP/数据库，两角色账号 CRUD/禁用、公开注册保持兼容、角色注入拒绝、共享管理域、安全 DTO、旧版本/幂等冲突、禁用/角色变更/删除即时撤销、三种并发移除最后管理员保护、匿名化保留历史外键、审计失败全事务回滚 |
| DT08-F（连接隔离部分） | interface/normal/persistence.test.ts，DT08-F | 测试工具，拒绝生产主机/库名/用户/缺运行标识；不代表生产数据脱敏工具已交付 |

执行证据见 docs/changes/build-exam-backend/verification.md；机器报告位于 backend/artifacts/test/<run-id>/junit.xml 和 run.json。报告不含连接 URL、令牌或密码；run.json 的 cleaned=true 才能证明该运行清理成功。
