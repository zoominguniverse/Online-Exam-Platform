# Proposal

## Why

README 已明确在线考试平台的必要功能，需要以独立 API 服务和 PostgreSQL 支撑全部考试管理能力，并把 reference 的一致性、接口契约与数据纪律作为开发基线。

## What Changes

- 采用前后端分离：前端经版本化 HTTP JSON API 调用后端，数据库仅由后端访问；前后端可独立构建和部署。
- 实现学生注册、账号管理、学科题库、三类试卷、任务、作答、批改、记录、错题、消息、动态、统计与审计查询。
- 统一分层依赖、资源路由、响应信封、错误码、边界校验、分页排序筛选、环境配置。
- 在 PostgreSQL 落实唯一约束、版本条件更新、事务、幂等与 Outbox，保护任务一次性与提交一致性。
- 建立数据字典、迁移与回滚、归属外键、审计、留存删除和跨存储补偿契约。

## Capabilities

### New Capabilities

- `exam-api`: 考试平台各业务资源及状态操作的服务端行为。
- `api-contracts`: API 分层、协议、校验、错误、幂等与查询规则。
- `exam-data`: PostgreSQL 模型、约束、事务、迁移和数据生命周期。

### Modified Capabilities

无；当前没有主规格。

## Impact

后续新增 `backend/`、API 契约、迁移、数据字典及服务配置。PostgreSQL 是业务数据唯一权威存储，图片位于私有文件存储，元数据和归属由 PostgreSQL 管理。框架与依赖版本须在实施时登记、锁定，不假定仓库已有技术栈。

依赖 `design-exam-product` 的业务契约；认证会话与授权实现由 `secure-exam-platform` 负责，必须与业务接口共同验收后才可发布。后端不提供绕过安全校验的临时公开接口。参考 [整体规划](../planning-overview.md)。
