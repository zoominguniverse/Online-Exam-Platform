# Proposal

## Why

README 的功能清单和 reference 的检查项需要形成可重复执行的证据。必须统一场景追溯、数据隔离、成败信号与发布门禁，才能判断五个领域是否真正完成。

## What Changes

- 建立单元、接口与主路径端到端三层测试，按正常／失败／边界组织并双向追溯每个规格场景。
- 使用隔离 PostgreSQL 数据与可注入时钟、外部适配器替身，保证随机顺序和离线运行。
- 统一业务码优先的缺陷信号、四类缺陷定义、有效断言、正向耗时两档阈值。
- 提供一键测试和门禁命令，失败阻断，跳过登记且数量不增加。
- 承接全部领域的完成定义、评审分工、规模上限、责任时限、例外书面化与证据归档。

## Capabilities

### New Capabilities

- `verification-system`: 分层、追溯、隔离、断言、耗时和测试执行契约。
- `change-assurance`: 完成定义、评审门禁、例外及变更证据治理。

### Modified Capabilities

无；当前没有主规格。

## Impact

后续交付 `tests/`、`scripts/test.ps1`、`scripts/gate.ps1`、CI 规则、README 运行说明及 `docs/changes/<change-name>/` 证据。这些是计划路径，当前不声称脚本或测试已经存在。

依赖其他四项规格作为场景来源；测试基础可先建设，完整验收依赖后端、安全、部署交付和真实前端集成。原型或 mock 不能替代真实前后端端到端通过记录。参考 [整体规划](../planning-overview.md)。
