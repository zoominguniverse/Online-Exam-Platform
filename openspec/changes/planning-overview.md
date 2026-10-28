# 五项 OpenSpec Change 总览与验收覆盖

本规划依据 [README.md](../../README.md) 和 [规范验收标准](../../reference/规范验收标准.md)，按用户要求建立五个 change。所有实施任务尚未执行；本目录只交付规划，不代表功能或上线验收已通过。

## Change 清单

| 领域 | Change | 规格能力 | 交付范围 |
| --- | --- | --- | --- |
| 产品设计 | [design-exam-product](design-exam-product/proposal.md) | exam-experience、interaction-standards | 29 项功能的页面与流程、四态、交互原型、视觉与可达性 |
| 后端开发 | [build-exam-backend](build-exam-backend/proposal.md) | exam-api、api-contracts、exam-data | 前后端分离 API、PostgreSQL、全业务、事务与数据生命周期 |
| 安全开发 | [secure-exam-platform](secure-exam-platform/proposal.md) | identity-access、content-security、security-governance | 身份会话、逐资源授权、附件输入输出、机密与供应链 |
| 测试 | [verify-exam-platform](verify-exam-platform/proposal.md) | verification-system、change-assurance | 分层追溯验证、一键门禁、评审与完成定义 |
| 部署运维 | [operate-exam-platform](operate-exam-platform/proposal.md) | runtime-delivery、service-observability、data-recovery | 独立镜像、三环境、发布回滚、观测容量、备份恢复 |

每个 change 均包含 proposal.md、design.md、tasks.md 和上述能力各自的 specs/<capability>/spec.md，未跳过任何规划 artifact。

## 依赖和实施顺序

1. 产品设计冻结角色、页面与业务规则；测试 change 的追溯／门禁基础和安全授权契约先建立，使后续每组工作能带验证交付。
2. 后端服务／PostgreSQL 基础与安全会话接入，接口默认拒绝；再按账号、题库试卷、任务答卷、消息统计推进。后端负责业务校验，安全负责授权机制和独立攻击验证，两者合并验收。
3. 测试随每个业务组补单元和接口用例，运维同步建设镜像、环境、健康和观测，不等业务全部写完才开始。
4. 产品设计仅交付设计与原型；本次五个分类未另含生产前端开发。真实前端实现是浏览器安全、端到端和前端镜像的显式外部交付依赖，需另行安排，不能以原型替代或声称这五项自然补齐前端代码。
5. 真实前后端集成后执行安全与端到端回归，再验容量、回滚、恢复和删除副本，达到全部完成定义后才可做平台发布验收。

依赖指“输入就绪与联合验收”，不要求将整个上游 change 先归档，避免后端／安全／测试／运维互相等待。每次实施 PR 一个任务组且最多 20 个手写文件；领域 change 保持用户要求的五个，不把大型单次 PR 当作实现方式。

## 共同约束与提案基线

- 用户明确约束：前后端分离、PostgreSQL。拟议后端栈、数据留存期、容量、配额、超时和恢复目标是本提案设计基线，不是既有实现或现状测量。
- README 29 项功能的角色、触发及路由映射见 [产品设计](design-exam-product/design.md)；接口、表关系和事务见 [后端设计](build-exam-backend/design.md)。
- README 留白按本次设计明确：任务每人一份答卷、发布时冻结目标学生；三类试卷均使用自行批改基线；时段采用左闭右开区间；到期冻结已保存草稿；成绩和题目版本保留历史。后续改动须同步相关规格。
- 本期不新增 AI、监考、教师角色、业务导入导出。reference 相关条目保留明确适用性与启用门槛，不能当作漏项；备份不是业务导出。
- 数据、安全与运维职责交叉但要求不重复定义：数据字典由后端负责，脱敏表由安全负责，备份副本及恢复由运维负责；测试负责证据和门禁。
- 业务成败以 code 判定，HTTP 映射单独检查；正向单例耗时与容量 p95 分开判定。具体阈值见 VT08、OB03。
- 所有 change 遵循 QA01–QA06：场景、用例、门禁、文档同步、具名评审和 docs 证据齐备才完成。当前 tasks 不勾选，docs 等路径是后续交付路径。

## Reference 逐项覆盖

下表保留来源条目原名；链接指向主责规格，编号定位 Requirement，其下 Scenario 给出 WHEN／THEN 验收方法。来源共 86 条；每条都必须有映射。UI07 与 AI 十项为条件适用，当前也要核验“没有隐式启用”的证据。

### 产品设计（10 项）

| 来源条目 | 主责要求 | 本期验收与适用性 |
| --- | --- | --- |
| 用户与场景 | [PX01](design-exam-product/specs/exam-experience/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 页面清单与信息架构 | [PX01](design-exam-product/specs/exam-experience/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 视图状态完备性 | [UI01](design-exam-product/specs/interaction-standards/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 文案与术语 | [UI02](design-exam-product/specs/interaction-standards/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 表单与校验 | [UI03](design-exam-product/specs/interaction-standards/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 视觉基线 | [UI04](design-exam-product/specs/interaction-standards/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 可达性与响应式 | [UI05](design-exam-product/specs/interaction-standards/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 变更可追溯 | [UI06](design-exam-product/specs/interaction-standards/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 状态与流程 | [PX08](design-exam-product/specs/exam-experience/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 导入导出 | [UI07](design-exam-product/specs/interaction-standards/spec.md) | 当前无入口；检查适用性登记；未来启用须先验证模板、限额、预览、整批与脱敏异步 |

### 后端开发（12 项）

| 来源条目 | 主责要求 | 本期验收与适用性 |
| --- | --- | --- |
| 分层与依赖方向 | [CT01](build-exam-backend/specs/api-contracts/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 接口命名与版本控制 | [CT02](build-exam-backend/specs/api-contracts/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 请求与响应结构 | [CT03](build-exam-backend/specs/api-contracts/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 错误码体系 | [CT03](build-exam-backend/specs/api-contracts/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 参数校验的位置 | [CT04](build-exam-backend/specs/api-contracts/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 数据访问与事务 | [DT04](build-exam-backend/specs/exam-data/spec.md)、[DT05](build-exam-backend/specs/exam-data/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 事务内的禁止项 | [DT04](build-exam-backend/specs/exam-data/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 幂等与并发 | [CT05](build-exam-backend/specs/api-contracts/spec.md)、[DT02](build-exam-backend/specs/exam-data/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 分页、排序与筛选 | [CT06](build-exam-backend/specs/api-contracts/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 排序的唯一兜底键 | [CT06](build-exam-backend/specs/api-contracts/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 非法筛选的失败策略 | [CT06](build-exam-backend/specs/api-contracts/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 配置外置 | [CT07](build-exam-backend/specs/api-contracts/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |

### 安全开发（14 项）

| 来源条目 | 主责要求 | 本期验收与适用性 |
| --- | --- | --- |
| 默认拒绝（default deny） | [IA01](secure-exam-platform/specs/identity-access/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 逐资源授权判定 | [IA02](secure-exam-platform/specs/identity-access/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 附件与文件读取 | [CS01](secure-exam-platform/specs/content-security/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 越权失败场景 | [IA07](secure-exam-platform/specs/identity-access/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 认证与会话 | [IA03](secure-exam-platform/specs/identity-access/spec.md)、[IA05](secure-exam-platform/specs/identity-access/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 令牌的存放位置 | [IA04](secure-exam-platform/specs/identity-access/spec.md)、[IA05](secure-exam-platform/specs/identity-access/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 密钥与机密 | [SG01](secure-exam-platform/specs/security-governance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 外部内容不可信 | [SG04](secure-exam-platform/specs/security-governance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 出站地址白名单 | [CS05](secure-exam-platform/specs/content-security/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 输入校验（validation） | [CS02](secure-exam-platform/specs/content-security/spec.md)、[CT04](build-exam-backend/specs/api-contracts/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 输出转义（escaping） | [CS03](secure-exam-platform/specs/content-security/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 敏感数据 | [CS04](secure-exam-platform/specs/content-security/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 依赖引入与许可证 | [SG02](secure-exam-platform/specs/security-governance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 依赖漏洞扫描 | [SG03](secure-exam-platform/specs/security-governance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |

### 测试（12 项）

| 来源条目 | 主责要求 | 本期验收与适用性 |
| --- | --- | --- |
| 测试层次 | [VT01](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 用例来源 | [VT02](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 三类组织 | [VT01](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 数据隔离 | [VT03](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 外部依赖 | [VT04](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 一键运行 | [VT05](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 通过判定 | [VT05](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 断言有效性的判据 | [VT06](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 缺陷判定的信号口径 | [VT07](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 缺陷的四个判定词 | [VT07](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 响应时间阈值 | [VT08](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 变更要求 | [VT06](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |

### 部署运维（11 项）

| 来源条目 | 主责要求 | 本期验收与适用性 |
| --- | --- | --- |
| 可复现构建 | [RD01](operate-exam-platform/specs/runtime-delivery/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 环境与配置 | [RD02](operate-exam-platform/specs/runtime-delivery/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 健康检查（health check） | [RD03](operate-exam-platform/specs/runtime-delivery/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 结构化日志（structured logging） | [OB01](operate-exam-platform/specs/service-observability/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 指标与告警 | [OB02](operate-exam-platform/specs/service-observability/spec.md)、[OB06](operate-exam-platform/specs/service-observability/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 发布与回滚 | [RD04](operate-exam-platform/specs/runtime-delivery/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 数据变更顺序 | [RD05](operate-exam-platform/specs/runtime-delivery/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 备份与恢复 | [RC01](operate-exam-platform/specs/data-recovery/spec.md)、[RC02](operate-exam-platform/specs/data-recovery/spec.md)、[RC04](operate-exam-platform/specs/data-recovery/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 性能与容量 | [OB03](operate-exam-platform/specs/service-observability/spec.md)、[OB04](operate-exam-platform/specs/service-observability/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 限流与配额 | [OB05](operate-exam-platform/specs/service-observability/spec.md)、[OB04](operate-exam-platform/specs/service-observability/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 发布留痕 | [RD06](operate-exam-platform/specs/runtime-delivery/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |

### AI应用（10 项）

| 来源条目 | 主责要求 | 本期验收与适用性 |
| --- | --- | --- |
| 调用边界 | [SG05](secure-exam-platform/specs/security-governance/spec.md)、[SG04](secure-exam-platform/specs/security-governance/spec.md) | 本期无模型；以后逐点区分确定性规则与模型职责 |
| 工具落点 | [SG05](secure-exam-platform/specs/security-governance/spec.md)、[SG04](secure-exam-platform/specs/security-governance/spec.md) | 本期无模型工具；以后只能调用既有授权服务，禁止直连存储 |
| 提示词即代码 | [SG05](secure-exam-platform/specs/security-governance/spec.md)、[SG04](secure-exam-platform/specs/security-governance/spec.md) | 本期无提示词；以后模板版本入库并附评测和评审 |
| 模型与参数锁定 | [SG05](secure-exam-platform/specs/security-governance/spec.md)、[SG04](secure-exam-platform/specs/security-governance/spec.md) | 本期无模型配置；以后锁模型版本、温度、输出上限和阈值 |
| 超时、重试与降级 | [SG05](secure-exam-platform/specs/security-governance/spec.md)、[SG04](secure-exam-platform/specs/security-governance/spec.md) | 本期无模型依赖；以后声明超时、重试上限、退避与明确错误 |
| 输出校验 | [SG05](secure-exam-platform/specs/security-governance/spec.md)、[SG04](secure-exam-platform/specs/security-governance/spec.md) | 本期无模型输出；以后结构校验失败即拒绝，不静默默认 |
| 不编造依据 | [SG05](secure-exam-platform/specs/security-governance/spec.md)、[SG04](secure-exam-platform/specs/security-governance/spec.md) | 本期无检索引用；以后空引用固定说明，不伪造依据 |
| 外部内容的装配方式 | [SG05](secure-exam-platform/specs/security-governance/spec.md)、[SG04](secure-exam-platform/specs/security-governance/spec.md) | 本期不装配提示词；以后材料放用户消息、系统禁止执行指令 |
| 评测集与回归 | [SG05](secure-exam-platform/specs/security-governance/spec.md)、[SG04](secure-exam-platform/specs/security-governance/spec.md) | 本期无模型评测；以后维护必须答对／必须拒答及版本回归 |
| 用量与成本观测 | [SG05](secure-exam-platform/specs/security-governance/spec.md)、[SG04](secure-exam-platform/specs/security-governance/spec.md) | 本期无模型费用；以后记模型、模板版本、耗时、用量、命中数，不记全文 |

### 数据（9 项）

| 来源条目 | 主责要求 | 本期验收与适用性 |
| --- | --- | --- |
| 命名与类型 | [DT01](build-exam-backend/specs/exam-data/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 主键与外键 | [DT02](build-exam-backend/specs/exam-data/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 审计字段 | [DT03](build-exam-backend/specs/exam-data/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 迁移纪律 | [DT05](build-exam-backend/specs/exam-data/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 数据字典 | [DT01](build-exam-backend/specs/exam-data/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 敏感数据分级 | [CS04](secure-exam-platform/specs/content-security/spec.md)、[DT06](build-exam-backend/specs/exam-data/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 留存与删除 | [DT06](build-exam-backend/specs/exam-data/spec.md)、[RC03](operate-exam-platform/specs/data-recovery/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 跨存储一致性 | [DT07](build-exam-backend/specs/exam-data/spec.md)、[RC02](operate-exam-platform/specs/data-recovery/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 测试数据 | [DT08](build-exam-backend/specs/exam-data/spec.md)、[VT03](verify-exam-platform/specs/verification-system/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |

### 评审与完成定义（8 项）

| 来源条目 | 主责要求 | 本期验收与适用性 |
| --- | --- | --- |
| 完成定义（Definition of Done） | [QA01](verify-exam-platform/specs/change-assurance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 变更规模上限 | [QA02](verify-exam-platform/specs/change-assurance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 评审检查表 | [QA02](verify-exam-platform/specs/change-assurance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 责任人与时限 | [QA03](verify-exam-platform/specs/change-assurance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 门禁必跑 | [QA04](verify-exam-platform/specs/change-assurance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 例外书面化 | [QA05](verify-exam-platform/specs/change-assurance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 证据归档 | [QA06](verify-exam-platform/specs/change-assurance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |
| 复盘留痕 | [QA06](verify-exam-platform/specs/change-assurance/spec.md) | 执行所列要求下全部适用场景，保存用例或人工检查证据 |

## 审阅入口与后续使用

先阅读各 proposal 的范围，再审阅 design 的拟议决策、specs 的验收场景和 tasks 的实施／验证清单。规格可评审并具备实施所需的规划材料；代码、应用测试和运维演练尚未执行。

审阅后可明确要求应用某个 change，例如 `$openspec-apply-change design-exam-product`。实施时遵守依赖顺序，每个任务组同步测试和文档；满足完成定义后再另行归档。

