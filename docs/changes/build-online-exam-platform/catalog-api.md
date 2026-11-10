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
| GET /me/papers、GET /me/papers/{id} | 仅可见同年级已发布且未归档的普通练习卷。列表可按 `type` 和 `status` 过滤，响应仅含试卷元信息 |
| GET/POST /admin/tasks | 列表可按 `gradeId`、`state` 筛选；新建接收 `gradeId`、`name`、`paperIds` |
| GET/PATCH/DELETE /admin/tasks/{id} | 详情含冻结的卷和名单人数；修改携带 `draft` 与 `expectedVersion`；删除携带 `expectedVersion` |
| POST /admin/tasks/{id}/publish | 携带 `expectedVersion`，同一事务冻结卷版本和启用学生名单 |
| GET /me/tasks、GET /me/tasks/{id} | 以发布时冻结的名单授权，逐卷返回标识、标题、时长及当前状态 |

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
