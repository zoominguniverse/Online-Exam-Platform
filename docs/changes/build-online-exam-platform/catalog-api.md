# 学科、题库、试卷与任务 API

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
| GET /me/attempts/{id} | 仅本人可读答卷、冻结题目快照及最新已保存答案；他人答卷为 404。交卷和到期冻结尚属后续任务。 |
| PUT /me/attempts/{id}/answers | 请求 `{expectedVersion,answerItems:[{questionId,answer}]}`，整份替换已保存答案；遗漏题目或 `answer:null` 清空该题。答案按题型分别为 `optionId`、`optionIds`、`texts`、`text`。以答卷快照校验题目与选项，单题非法则整批 400 且版本不变；旧版本 409 `VERSION_CONFLICT`；截止或非作答状态 409 `EXAM_CLOSED`。成功返回最新答卷、答案、版本与 `serverNow`。Web 需 CSRF。 |
| GET/POST /admin/tasks | 列表可按 `gradeId`、`state` 筛选；新建接收 `gradeId`、`name`、`paperIds` |
| GET/PATCH/DELETE /admin/tasks/{id} | 详情含冻结的卷和名单人数；修改携带 `draft` 与 `expectedVersion`；删除携带 `expectedVersion` |
| POST /admin/tasks/{id}/publish | 携带 `expectedVersion`，同一事务冻结卷版本和启用学生名单 |
| GET /me/tasks、GET /me/tasks/{id} | 以发布时冻结的名单授权，逐卷返回标识、标题和时长。逐卷状态投影尚属 6.4，当前列表状态不能用作答卷完成依据 |
| POST /me/tasks/{taskId}/papers/{paperId}/attempts | 冻结成员经任务入口首次开始或恢复任务卷；Web 需 CSRF，小程序可用本人令牌。校验任务已发布、卷关联及归档状态；首次使用任务冻结的卷版本建答卷，同一学生同一任务卷后续请求返回原答卷。可选 `Idempotency-Key` 同普通卷契约，同键换卷返回 409；无资格或未关联返回 404，拒绝不落库。已开始答卷即使任务或卷随后归档仍可恢复，截止不重置。 |

答案自动保存按账号与答卷存储待同步草稿，并记录服务端基准版本和本地修订号。网络请求结果未知时，客户端先读取答卷：若服务端已保存相同答案则确认，若版本变化但答案不同则保留双方内容并提示冲突，不自动覆盖。保存中再次编辑不会由旧响应清除新草稿；退出或换账号清理原账号草稿。服务端返回 `EXAM_CLOSED` 后停止重试，未确认的本地答案不计入服务器记录。

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

题干只允许净化后的受限 HTML。图片写作 `<img data-attachment="图片 UUID" alt="说明">`，由预览根据题目及版本生成访问地址。上传限 PNG、JPEG 或 WebP，最大 5 MiB 和 1,600 万像素，依据解码结果判断类型。每题最多 20 张图片，未引用图片 24 小时后清理。数学公式的跨端渲染仍在任务 4.4。

## 试卷与任务

试卷类型为 `FIXED`、`TIMED` 和 `TASK`。草稿接受 `name`、`durationMinutes`（1–180）、时段卷的 `opensAt`、`closesAt`，以及顺序排列的 `groups`，每组格式为 `{"name":"选择题","questionIds":["题目 UUID"]}`。每卷最多 200 道不同的同学科题目，发布时至少有一道。发布保留草稿选定的版本及分值，之后再编辑会生成独立草稿。时段开始时刻含、结束时刻不含，列表显示 `UPCOMING`、`OPEN` 或 `CLOSED`。

发布年级任务时，按输入顺序冻结当前已发布、同年级且未归档的任务卷，以及当时启用的该年级学生。每张卷最多绑定一个已发布任务。已发布任务归档后保留卷和成员，任务进度随答卷功能接入。
