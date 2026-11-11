> 历史复核：以下43/53为授权勾选时的快照。随后10项缺口已补齐，当前53/53，见[收尾验收](completion-acceptance.md)。

# 授权勾选后的任务复核

用户于2026-11-11明确授予按证据勾选的权限。本次逐项核对当前任务行、实现与既有成功记录，新增勾选26项；每勾选一项即重跑OpenSpec apply并校验进度。现为 **43/53**，apply仍为 **ready**，剩余10项。此前17项维持原状态。本次只更新任务与交接文档，不修改业务代码、不提交、推送、合并或归档。

此前把剩余36项统称为“仅待审阅”不准确。Web技术门禁通过仅表示现有测试通过；指定场景未执行或规划入口不一致时，仍不能勾选对应任务。旧验收文件保持当时进度和时间，不改写历史结果，当前状态以本文和tasks.md为准。

## 尚未完成的任务

| 任务 | 具体缺口 |
| --- | --- |
| 4.4 | 共享呈现与分式/图片/长表已验证；尚无上下标和矩阵样例在管理预览与学生Web两端的呈现证据。 |
| 5.4 | 学生试卷页与服务端时段边界测试存在；真实浏览器尚未断言UPCOMING/CLOSED的时间展示、禁用开始及与服务端同刻判定一致。 |
| 6.2 | 目标学生两卷任务主流程已过；尚未完成非目标学生直接访问任务页面被拒绝的浏览器用例（接口拒绝已有测试）。 |
| 9.3 | 资料/头像、创建时间和字段限制已有实现；缺修改后重新登录与另一独立Web会话一致、管理员改手机号及真实创建时间不变的完整LR03-N证据。 |
| 10.1 | 发送事务/非法收件人/去重/幂等测试已过；MA01-N指定A、B、A群发后A/B各一份、A双会话阅读的组合场景尚未完整执行。 |
| 10.2 | 管理发送/详情及学生阅读页面已过；现有浏览器只在同一会话刷新，缺两个独立Web会话多次阅读后已读人数仍为一的联调证据。 |
| 10.5 | API与时区文档已交付；design功能表与实际路由未完全对齐，例如/admin/users/student与实际students、/admin/logs与activities，学科/任务独立编辑路径也未实现（现为列表内编辑）。需统一规范路由并逐一验证。 |
| 11.1 | 38路由三宽度、32数据页错误恢复及登录焦点/单字段失焦已过；尚未逐页记录空状态、全部表单校验/处理中重复操作/失败输入保留和完整键盘作答的证据。 |
| 12.4 | 30项Requirement/71项Scenario有索引，两项变异被拒绝；以上场景仍缺具体证据，映射存在不等于验收完整，需补齐后复核覆盖。 |
| 12.5 | 材料与Web技术门禁已过；仍有未完成任务与规范路由/验收证据缺口，完整平台门禁及发布评审交付尚未完成。 |

这些任务多数已有功能实现，需要补齐明确的验收步骤；10.5还涉及规划与实际入口的一致性。建议先补4.4/5.4/6.2/9.3/10.1/10.2和11.1，完成10.5路由对齐，再收尾12.4/12.5。没有把此前不要求发布的真实生产HTTPS域名、实际值班姓名或线上定时备份配置计入已部署；这些仍是投产配置。依赖公告见[依赖审计](dependencies.md)，需在发布评审中处理，未擅自升级锁定版本。

## 本次确认完成并勾选

本次沿用刚完成的真实Node3/3、Java28/28、Chromium9/9、100会话600秒、100份定时截止、变异和最终镜像发布/恢复证据，未将此轮文档审阅说成再次跑过全部业务测试。证据入口见[上一轮技术验收](evidence/2026-11-11-web-scope/README.md)。

| 任务 | 完成依据 | 当前实现/可复跑证据 |
| --- | --- | --- |
| 1.3 | 两个入口真实API版本/请求阻断/重试通过。 | [StatusPage.vue](../../../web/shared/StatusPage.vue)、[platform-workflow.spec.ts](../../../web/e2e/platform-workflow.spec.ts) |
| 4.5 | 题型/附件限制与示例；题目版本归档、私有图片归属和答案不泄露测试通过。 | [catalog-api.md](catalog-api.md)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 5.5 | 发布版本、并发冲突、冻结引用与归档后原答案回看已通过。 | [data-dictionary.md](data-dictionary.md)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 6.4 | 逐卷进度、待批改不算完成、归档阻止新开始、最终两卷完成投影通过。 | [AssignmentsPage.vue](../../../web/student/src/AssignmentsPage.vue)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 7.1 | 普通/任务开始与恢复、快照/时段截止/安全DTO通过。 | [AttemptService.java](../../../backend/src/main/java/com/onlineexam/platform/service/AttemptService.java)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 7.2 | 全量保存、离线草稿、冲突、未知响应、换账号隔离均有真实浏览器成功记录。 | [attempt-saving.spec.ts](../../../web/e2e/attempt-saving.spec.ts)、[AttemptPage.vue](../../../web/student/src/AttemptPage.vue) |
| 7.3 | 原子交卷、截止竞争/补处理、worker锁与真实API停机恢复通过。 | [AttemptLifecycle.java](../../../backend/src/main/java/com/onlineexam/platform/service/AttemptLifecycle.java)、[attempt-saving.spec.ts](../../../web/e2e/attempt-saving.spec.ts) |
| 7.4 | 题号、标记、保存状态、实际未答确认及可信隐藏/显示后的取时通过。 | [platform-workflow.spec.ts](../../../web/e2e/platform-workflow.spec.ts)、[AttemptPage.vue](../../../web/student/src/AttemptPage.vue) |
| 7.6 | 状态与提交载荷文档齐；卷外/重复/非法选项/超限载荷整体拒绝通过。 | [catalog-api.md](catalog-api.md)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 8.1 | 整数快照计分、乱序、少选/多选/未答及小数精度通过；计分变异被断言拒绝。 | [AttemptLifecycle.java](../../../backend/src/main/java/com/onlineexam/platform/service/AttemptLifecycle.java)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 8.2 | 本人全套主观分、空答案零分、非法整体拒绝、幂等/并发终态通过。 | [LearningService.java](../../../backend/src/main/java/com/onlineexam/platform/service/LearningService.java)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 8.3 | 提交前参考内容不可读，提交后可评分，客观不可改/SELF/最终成绩通过。 | [ReviewPage.vue](../../../web/shared/ReviewPage.vue)、[platform-workflow.spec.ts](../../../web/e2e/platform-workflow.spec.ts) |
| 8.4 | 分数与错题失败注入整笔回滚；最终成绩一致后任务投影通过。 | [LearningService.java](../../../backend/src/main/java/com/onlineexam/platform/service/LearningService.java)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 8.5 | 两独立Web会话并发确认、终态重试不重复加分及规则/接口索引通过。 | [scenarios.md](scenarios.md)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 9.1 | 本人/管理成绩列表详情、待批改暂定标识、筛选/归属拒绝通过。 | [RecordsPage.vue](../../../web/shared/RecordsPage.vue)、[ReviewPage.vue](../../../web/shared/ReviewPage.vue) |
| 9.2 | 低于满分/未答收录、最近错误去重、后来答对保留及页面通过。 | [MistakesPage.vue](../../../web/student/src/MistakesPage.vue)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 9.4 | 题目修改归档、试卷归档后旧题分值/答案/原图和成绩保持，通过真实Web回看。 | [platform-workflow.spec.ts](../../../web/e2e/platform-workflow.spec.ts)、[data-dictionary.md](data-dictionary.md) |
| 10.3 | 事务审计、本人动态、管理筛选、requestId关联和日志脱敏通过。 | [AuditConfiguration.java](../../../backend/src/main/java/com/onlineexam/platform/configuration/AuditConfiguration.java)、[ActivityPage.vue](../../../web/shared/ActivityPage.vue) |
| 10.4 | 香港自然日去重/补零/月初测试和真实管理图表路由通过。 | [CommunityService.java](../../../backend/src/main/java/com/onlineexam/platform/service/CommunityService.java)、[DashboardPage.vue](../../../web/admin/src/DashboardPage.vue) |
| 11.2 | 分页/查询白名单、幂等键冲突、Cookie/CSRF、撤销、资源归属与JSON边界通过。 | [Contracts.java](../../../backend/src/main/java/com/onlineexam/platform/domain/Contracts.java)、[IdentityIntegrationTest.java](../../../backend/src/test/java/com/onlineexam/platform/IdentityIntegrationTest.java) |
| 11.3 | 最终API/Web镜像、Nginx空库启动/健康与在途提交优雅停止通过。 | [Dockerfile](../../../web/Dockerfile)、[final-release-report.json](evidence/2026-11-11-web-scope/final-release-report.json) |
| 11.4 | 指标/连接池、脱敏JSON、限流、断库/截止/持续5xx告警及值班运维处置材料齐。 | [ObservabilityService.java](../../../backend/src/main/java/com/onlineexam/platform/service/ObservabilityService.java)、[operations.md](operations.md) |
| 11.5 | 一致性DB/文件备份及隔离恢复、较新删除清单、撤销旧会话及实测RPO/RTO通过。 | [restore.mjs](../../../scripts/restore.mjs)、[final-release-report.json](evidence/2026-11-11-web-scope/final-release-report.json) |
| 11.6 | 真实c63f6e9配对API/Web回退、原答案原图与路由切换通过。 | [release.mjs](../../../scripts/release.mjs)、[final-release-report.json](evidence/2026-11-11-web-scope/final-release-report.json) |
| 12.1 | 真实管理UI五题型/两任务卷、Web多会话保存恢复、提交/SELF/错题历史/管理查分全套通过。 | [platform-workflow.spec.ts](../../../web/e2e/platform-workflow.spec.ts)、[attempt-saving.spec.ts](../../../web/e2e/attempt-saving.spec.ts) |
| 12.3 | 100会话600秒、实际硬件/数据量、100份定时截止及指定故障恢复满足预算。 | [capacity.mjs](../../../scripts/capacity.mjs)、[capacity-report.json](evidence/2026-11-11-web-scope/capacity-report.json) |

机器可读的逐项进度确认和理由见[复核记录](task-review.json)。本次规格、契约及diff校验结果与平台预检见[复核检查](task-review-checks.json)。
