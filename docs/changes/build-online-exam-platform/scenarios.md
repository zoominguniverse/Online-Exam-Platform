# 场景索引

| 场景 | 证据 | 范围 |
| --- | --- | --- |
| PD02-F | [Docker 验证](verification.md) | PostgreSQL 停机、恢复和缺少配置 |
| PD02-N | `scripts/test.mjs` 和 `VersionIntegrationTest` | 空库迁移、就绪及 API 版本。完整场景仍需微信登录 |
| CX01-F | 管理端和学生端路由的浏览器验证 | 未知地址返回入口。角色和登录门禁在身份闭环验收 |
| PD03-F | `node scripts/test.mjs --negative-control` | 在隔离接口测试中比较错误的版本，验证脚本以非零退出 |
| IA01-N / IA01-F | `IdentityIntegrationTest.validatesRegistrationAndKeepsCredentialsOutOfResponsesAndLogs` | 规范化重复、无效年级、注入角色、CSRF、密码存储及脱敏、退出后旧 Cookie 失效 |
| IA01-B | `IdentityIntegrationTest.managesAccountsAndRevokesAllSessionsWhenDisabled`、`handlesUpstreamFailuresAndPreventsConcurrentBindingToTwoAccounts` | Web 多会话及微信替身旧令牌均被撤销；恢复启用须重新认证。体验版双端场景待验收 |
| IA02-N / IA02-B | `IdentityIntegrationTest.managesAccountsAndRevokesAllSessionsWhenDisabled`、`protectsTheLastEnabledAdministratorAcrossConcurrentHttpRequests` | 账号筛选、版本冲突、并发操作、保留软删除记录 UUID。历史答卷关联在答卷功能完成后验证 |
| IA02-F | `IdentityIntegrationTest.validatesRegistrationAndKeepsCredentialsOutOfResponsesAndLogs` | 学生直接访问管理查询和写入接口返回 403。答卷、消息和资料的资源边界随对应业务实现验证 |
| CX01-F | [Chrome 账号流程](verification.md#web-身份闭环) | 匿名重定向、双向角色拒绝、未知路由回入口 |
| IA03-F / IA03-B | `IdentityIntegrationTest.consumesCodesAndRevokesTokensWhilePreservingWebIdentity`、`handlesUpstreamFailuresAndPreventsConcurrentBindingToTwoAccounts` | 微信替身模拟无效 code、超时和重复绑定；真实 PostgreSQL 验证唯一约束、旧令牌撤销和 code 重放拒绝 |
| IA03-N | `IdentityIntegrationTest.consumesCodesAndRevokesTokensWhilePreservingWebIdentity`、`tests/wechat.test.cjs` | 本地验证绑定、自动登录、账号 UUID 一致、退出清理，真实体验版步骤见 [小程序体验版验收](wechat-experience.md) |
| QB01-N / QB01-F | `IdentityIntegrationTest.createsAllQuestionTypesAndPreservesVersionsWhenArchived` 和 Chrome | 学科创编、规范化唯一、未引用删除、题目引用阻止删除 |
| QB02-N / QB02-F | 同上 | 五题型创编与筛选、分值与答案校验、失败不落库 |
| QB02-B | 同上 | 新版本和归档保留旧题目。发布试卷读取固定题目版本，完整历史答卷回看待验收 |
| QB03-F / QB03-B | `IdentityIntegrationTest.validatesPrivateImagesAndCleansRolledBackReferences` | 危险属性净化、格式与大小、跨账号直读拒绝、关联失败回滚和孤立文件清理。答卷 DTO 和跨端富文本待验收 |
| PA01-N / PA01-F | `IdentityIntegrationTest.publishesAllPaperTypesAndFiltersStudentsAtTheExactWindowBoundaries`、`rollsBackPublishedVersionIfUpdatingThePaperFails` 和 Chrome | 三类卷发布、混合组卷、总分顺序、空卷/跨学科/时段校验及中途失败回滚 |
| PA02-F / PA02-B | `IdentityIntegrationTest.publishesAllPaperTypesAndFiltersStudentsAtTheExactWindowBoundaries` | 只验证练习卷元信息的越级拒绝、上下边界、归档及任务卷隔离。开始答卷行为待实现 |
| PA03-N / PA03-B | 同上 | 新草稿不覆盖发布版本，旧版本可读取，过期编辑返回 409。已开始答卷的历史保持待验收 |
| TA01-N / TA01-F / TA01-B | `IdentityIntegrationTest.freezesAssignmentPapersAndEligibilityAndRollsBackIfOnePaperIsAlreadyBound`、Chrome 和 `tests/wechat.test.cjs` | 两卷发布、目标学生可见、名单冻结、禁用撤销和整批回滚。Chrome 验证非目标学生直接访问失败，小程序页面逻辑由替身验证 |
| TA03-F | 同上 | 修改已发布任务返回 409，归档保留卷和成员。答卷进度联动待实现 |
