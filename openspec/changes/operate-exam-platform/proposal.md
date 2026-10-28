# Proposal

## Why

考试提交与成绩记录要求可持续服务和可恢复数据。需要把 reference 的部署运维标准落实为可复现构建、可观测运行、可回滚发布与 PostgreSQL 恢复演练，避免“能启动”被当成“可上线”。

## What Changes

- 建立前端、后端独立多阶段容器构建和同镜像跨开发／测试／生产环境部署。
- 提供存活、就绪、优雅退出、结构化日志、指标告警和按主体限流。
- 建立蓝绿发布、一键版本回滚、先结构后代码的兼容迁移流程。
- 规定 PostgreSQL 与私有文件备份、保留期、RPO／RTO、恢复演练及删除记录重放。
- 定义性能容量预算、批量与外部调用配额、发布记录与运维责任。

## Capabilities

### New Capabilities

- `runtime-delivery`: 构建、环境、健康检查、发布与迁移的运行契约。
- `service-observability`: 日志、指标、告警、容量与限流。
- `data-recovery`: 备份恢复、删除副本处理与演练证据。

### Modified Capabilities

无；当前没有主规格。

## Impact

后续新增容器文件、`deploy/`、发布与恢复脚本、README 运维说明、`docs/operations/` 和 `docs/releases/`。运行单元包含前端静态服务、独立 API、Outbox 工作进程、PostgreSQL 及私有文件存储；平台不绑定特定云厂商。

依赖后端迁移及健康协议、安全配置和测试门禁。缺少生产前端或演练证据时只能验收已有组件，不得发布“整个平台验收完成”结论。参考 [整体规划](../planning-overview.md)。
