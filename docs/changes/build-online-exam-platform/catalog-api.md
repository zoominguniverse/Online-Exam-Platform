# 学科、题库、试卷与任务 API

## Web规范入口补齐（2026-11-11）

本轮没有新增业务API，OpenAPI仍为73个操作。新增规范Web路径与现有入口复用同一受保护组件：管理端 /users/student、/users/admin 分别与 /users/students、/users/admins 等价，/logs与/activities等价；/subjects/{id}/edit、/tasks/{id}/edit通过实际标识读取并编辑原对象。学生 /exam/{id} 与 /attempts/{id} 等价，/attempts/{id}/grade 与 /attempts/{id}/review 使用同一授权结果页。原入口继续可用。

学科/任务直达页加载失败可重试原对象，未知对象不呈现可误提交的新建任务表单。普通卷/任务卷开始处理中统一禁用再次进入；内容/账号/登录表单在处理期间抑制重复提交。自动化使用实际网络延迟/断开，校验同一次处理只发一次写请求及失败后安全输入保留；密码按既有安全规则清空。


路径均以 `/api/v1` 开头。响应沿用身份 [OpenAPI](openapi.yaml) 的 `code`、`message`、`data`、`requestId` 外层结构。Web 写操作需获取 CSRF Cookie 和请求头。管理接口要求管理员身份，学生接口需要启用的学生身份。

| 方法与路径 | 请求及结果 |
| --- | --- |
| GET /admin/subjects | 按 `gradeId` 过滤，返回分页学科 |
| POST /admin/subjects | `gradeId`、`name`，返回新学科 |
| GET/PATCH/DELETE /admin/subjects/{id} | 详情；改名携带 `name` 与 `expectedVersion`；删除携带 `expectedVersion`。被题目或试卷引用时返回 409 |
| GET /admin/questions | 按 `subjectId`、`type` 筛选，返回分页当前未归档题目 |
| POST /admin/questions | 接收下文题目对象，返回保存后的完整版本 |
| GET/PATCH/DELETE /admin/questions/{id} | 详情可传 `version`；更新携带 `question` 与 `expectedVersion`；删除携带 `expectedVersion` 并归档 |
| POST /attachments | 管理员用 multipart 字段 `file` 上传，返回 `id`、`contentType`、`byteSize` |
| GET /attachments/{id} | 已被引用的图片只向上传者提供二进制 |
| GET /admin/questions/{question}/versions/{version}/attachments/{id} | 管理预览只能读取对应题目版本已引用的图片 |
| GET /admin/papers | 按 `subjectId`、`gradeId`、`type` 和 `includeArchived` 筛选 |
| POST /admin/papers | `subjectId`、`type`、`draft` |
| GET/PATCH/DELETE /admin/papers/{id} | 详情可用 `versionId` 获取指定快照；修改携带 `draft` 与 `expectedVersion`；删除携带 `expectedVersion`，已发布试卷改为归档 |
| POST /admin/papers/{id}/publish | 携带 `expectedVersion`，返回已发布内容和冻结题目 |
| GET /me/papers、GET /me/papers/{id} | 仅可见同年级已发布且未归档的固定卷、时段卷；任务卷不从此入口读取。列表可按 `type=FIXED/TIMED`、`status=OPEN/UPCOMING/CLOSED` 过滤。时段按服务端时钟判定：开始时刻为 `OPEN`，结束时刻为 `CLOSED`。响应仅含试卷元信息；开始请求再次校验资格。 |
| POST /me/papers/{id}/attempts | 同年级启用学生开始已发布未归档固定卷或开放中的时段卷。Web 需 CSRF；可选 `Idempotency-Key` 为 1–128 个可打印 ASCII 字符，24 小时内同学生同键返回原答卷，同键换卷冲突。无键或新键创建独立答卷。时段卷仅在开始时刻含、结束时刻不含的区间内创建；截止取时长与开放结束较早值。返回安全题目快照、`serverNow`、`deadlineAt`、版本和空 `savedAnswers`。 |
| GET /me/attempts/{id} | 仅本人可读答卷、冻结题目快照及最新已保存答案；他人答卷为 404。取得行锁后检查截止，必要时按已确认答案冻结并返回终态。此 GET 会执行到期补处理。 |
| PUT /me/attempts/{id}/answers | 请求 `{expectedVersion,answerItems:[{questionId,answer}]}`，整份替换已保存答案；遗漏题目或 `answer:null` 清空该题。答案按题型分别为 `optionId`、`optionIds`、`texts`、`text`。以答卷快照校验题目与选项，单题非法则整批 400 且版本不变；旧版本 409 `VERSION_CONFLICT`；截止或非作答状态 409 `EXAM_CLOSED`。成功返回最新答卷、答案、版本与 `serverNow`。Web 需 CSRF。 |
| POST /me/attempts/{id}/submit | 必填 `Idempotency-Key`（1–128 个可打印 ASCII 字符），载荷同全量保存。截止前版本匹配才全量保存并原子冻结/计分；单题非法整体拒绝。取得锁时已截止则仅冻结原已确认答案，返回 HTTP 200 的终态，迟到载荷不写入。24 小时内同账号同键同载荷重试返回原结果，同键异载荷/答卷 409；新键及过期键也不能改写冻结答案。Web 需 CSRF。 |
| GET/POST /admin/tasks | 列表可按 `gradeId`、`state` 筛选；新建接收 `gradeId`、`name`、`paperIds` |
| GET/PATCH/DELETE /admin/tasks/{id} | 详情含冻结的卷和名单人数；修改携带 `draft` 与 `expectedVersion`；删除携带 `expectedVersion` |
| POST /admin/tasks/{id}/publish | 携带 `expectedVersion`，同一事务冻结卷版本和启用学生名单 |
| GET /me/tasks、GET /me/tasks/{id} | 以发布时冻结的名单授权，逐卷返回标识、标题、时长、attemptId 和 NOT_STARTED/IN_PROGRESS/WAIT_SELF_GRADE/COMPLETED；completedCount/totalCount/completed 由各卷最终状态实时推导，待批改不算完成 |
| POST /me/tasks/{taskId}/papers/{paperId}/attempts | 冻结成员经任务入口首次开始或恢复任务卷；Web 需 Cookie 和 CSRF。校验任务已发布、卷关联及归档状态；首次使用任务冻结的卷版本建答卷，同一学生同一任务卷后续请求返回原答卷。可选 `Idempotency-Key` 同普通卷契约，同键换卷返回 409；无资格或未关联返回 404，拒绝不落库。已开始答卷即使任务或卷随后归档仍可恢复，截止不重置。 |

答案自动保存按账号与答卷存储待同步草稿，并记录服务端基准版本、本地修订号和待确认请求的答案副本。网络请求结果未知时先读取答卷，与待确认请求核对；确认成功后仍保留较新的本地输入继续保存。若服务端内容不同则保留双方并提示冲突，不自动覆盖。Web 展示服务器已保存内容，显式选择「加载服务器答案并丢弃本地草稿」时重新读取后再替换；读取失败保留草稿。清空最后一个多选视为清空本题。退出或换账号清理原账号草稿，并阻止旧页面异步回调恢复旧数据。

冻结共用账号→答卷锁顺序，获锁后采样可注入的服务端时钟。每 5 秒调度最多扫描 100 份到期答卷，竞争时跳过已锁账号/答卷，下轮再处理。读取、保存、提交均补处理；迟到保存返回 `EXAM_CLOSED`，但冻结事务仍提交，客户端随后读取终态。截止后未确认答案不计入。

答卷增加 `frozenAt`（实际冻结时刻）、`freezeReason=MANUAL/DEADLINE`、`automaticScore`、`finalScore` 和 `questionScores`。作答中时间/分数为空，冻结后客观题按快照与整数百分之一分计分，多选集合完全一致才满分；纯客观卷 `COMPLETED` 并在同一事务更新错题，混合卷 `WAIT_SELF_GRADE`、主观题与最终分数为空。参考答案与解析仍不在此安全 DTO 中开放。主观批改、成绩回看和任务完成投影已接入下述独立接口；Web 页面使用这些接口完成闭环。

列表使用 `page`（默认 1）和 `pageSize`（默认 20，最大 100）。

## 题型字段

所有题型包含 `subjectId`、`type`、`difficulty`（1–5）、正分值 `score`（字符串，保留两位小数）、`stemHtml`、`options`、`answer` 和可选 `explanationHtml`。题干和解析各最多 10,000 字符。选择题选项 2–10 个，标识在一题内唯一，选项文字最多 2,000 字符。

| 题型 | 正确答案示例 |
| --- | --- |
| `SINGLE` | `{"optionIds":["B"]}` |
| `MULTIPLE` | `{"optionIds":["A","C"]}`，至少两个不同有效选项 |
| `TRUE_FALSE` | `{"optionIds":["true"]}` 或 `{"optionIds":["false"]}` |
| `GAP` | `{"gaps":["甲","乙"]}`，题干使用从 1 开始的 `<span data-gap="1">____</span>`，最多 20 个空位，每空参考答案至多 1,000 字符 |
| `SHORT` | `{"text":"参考内容"}`，最多 10,000 字符 |

题干只允许净化后的受限 HTML。图片写作 `<img data-attachment="图片 UUID" alt="说明">`，由预览根据题目及版本生成访问地址。上传限 PNG、JPEG 或 WebP，最大 5 MiB 和 1,600 万像素，依据解码结果判断类型。每题最多 20 张图片，未引用图片 24 小时后清理。Web 使用锁定 KaTeX（trust=false、限制展开），长表格在内容内滚动。

## 试卷与任务

试卷类型为 `FIXED`、`TIMED` 和 `TASK`。草稿接受 `name`、`durationMinutes`（1–180）、时段卷的 `opensAt`、`closesAt`，以及顺序排列的 `groups`，每组格式为 `{"name":"选择题","questionIds":["题目 UUID"]}`。每卷最多 200 道不同的同学科题目，发布时至少有一道。发布保留草稿选定的版本及分值，之后再编辑会生成独立草稿。时段开始时刻含、结束时刻不含，列表显示 `UPCOMING`、`OPEN` 或 `CLOSED`。

发布年级任务时，按输入顺序冻结当前已发布、同年级且未归档的任务卷，以及当时启用的该年级学生。每张卷最多绑定一个已发布任务。已发布任务归档后保留卷和成员，归档阻止新开始并保留已有答卷；任务进度由每卷最终完成状态推导。

## 批改、记录及消息资料（2026-11-11）

| 方法与路径 | 契约 |
| --- | --- |
| GET /me/subjects | 本年级学科分页，供本人记录及错题筛选 |
| GET /me/attempts / GET /admin/attempts | 本人/全体记录；subjectId、status、page、pageSize、sort=startedAt，管理端另可 ownerId。开始时间倒序、UUID 兜底；未知筛选/排序 400。返回快照卷名、状态、客观/最终分数、用时、AUTO/SELF 来源；待批改明确暂定 |
| GET /me/attempts/{id}/review | 先校验本人和已提交，再返回 attempt、items、metadata、gradingSource；每题含 referenceAnswer、explanationHtml、awardedScore、subjective、empty。作答中 409；他人 404 |
| GET /admin/attempts/{id} | 管理员读取答卷与原版本参考信息，学生角色 403 |
| POST /me/attempts/{id}/self-grade | 必填 Idempotency-Key；{expectedVersion,scores:[{questionId,score}]}。仅全部主观题，分数十进制字符串、最多两位小数，0 至快照满分；空答案必须 0。客观题、缺题、重复、额外字段整体拒绝。唯一 grading、逐题成绩、最终总分、错题及派生进度同一事务；24 小时同键异载荷冲突，完成后另键同评分返回原结果，修改已确认分数409 |
| GET /me/mistakes / GET /me/mistakes/{questionId} | 本人最近错误快照，按学科分页；重复错误保留一条，后来答对不删除；含原学科、题型、作答、参考信息和答卷关联 |
| GET/PATCH /me/profile | 返回账号、姓名、手机号、生日、avatarId、version、真实创建时间；写入 expectedVersion/name/phone，学生可 birthday（1900 至香港今天或 null），管理员禁止生日字段。不可改角色、年级或状态，未知字段整体拒绝 |
| POST /me/avatar / GET /me/avatar/image | multipart file，PNG/JPEG/WebP 上限 5 MiB；只读本人头像，替换后原图片按无引用规则清理 |
| GET /me/attempts/{attempt}/questions/{question}/attachments/{id} | 校验本人快照和引用；作答中仅 STEM，提交后可读解析图片；无关图片或他人 404，no-store。管理员有对应 /admin/attempts/... 路径 |
| POST /admin/messages | 必填 Idempotency-Key；title 1–200、body 1–10000、recipientIds 去重后 1–500；任意不存在/禁用/删除收件人整体拒绝。返回 message 与 recipients，发布后不可改 |
| GET /admin/messages /{id} | 分页发送列表、明细、收件人数和已读人数；所有管理员可查看 |
| GET /me/messages /{id} | 本人收件分页/明细，不返回他人收件人或人数 |
| PUT /me/messages/{id}/read | 首次 readAt 固定，重复/并发阅读不重复累计；越权 404 |
| GET /me/activities | 只返回当前用户动态，拒绝伪造 ownerId |
| GET /admin/audit-events | actorId/requestId/action 分页筛选；仅安全主体、动作、目标、结果、时间和请求标识 |
| GET /admin/statistics | 未归档卷/题数量、最近七天活跃去重人数、本月逐日新增题目（补零），timeZone=Asia/Hong_Kong |
| GET /admin/observability | 最近五分钟量/5xx比例/p95、DB、截止滞后、告警及责任角色，详见 operations.md |

JSON 上限 2 MiB。Web 写入需要 CSRF 和 Cookie，当前不接受 Bearer 认证。响应 requestId 与 X-Request-Id、审计和结构化日志一致。未知 query 字段拒绝；分页 page>=1、pageSize 1–100。限流返回 429/Retry-After。结果未知时 Web 保留原交卷/批改/消息键和载荷，再核对或重试；确认前禁止编辑该待定操作。

## 当前身份范围

当前版本仅提供管理端和学生 Web 会话认证；个人接口仅接受Cookie，写请求全部要求CSRF。旧版移动身份入口不再注册，历史不透明令牌即使仍在数据库中也不能认证。旧迁移与表保留用于兼容回退，改密/禁用撤销历史凭据，账号删除/角色变更额外清除旧绑定；不会生成新凭据。
