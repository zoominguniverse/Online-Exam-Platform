# 后端依赖登记

运行环境 Node.js 24 或更高版本；所有直接和传递依赖锁定在 backend/package-lock.json。
安装使用 `npm ci --prefix backend`；许可证准入白名单为 MIT、ISC、BSD-2-Clause、BSD-3-Clause、Apache-2.0、PostgreSQL、Python-2.0、0BSD、CC0-1.0、Unlicense，双许可证须至少有一个可用许可。新增依赖需重新登记和审查。

| 依赖 | 精确版本 | 用途 | 许可证 |
| --- | --- | --- | --- |
| fastify | 5.12.8 | HTTP 服务及 JSON Schema 边界校验 | MIT |
| @fastify/cookie | 11.1.5 | 安全刷新 Cookie | MIT |
| @fastify/cors | 11.4.0 | 显式来源校验 | MIT |
| pg | 8.23.1 | PostgreSQL 参数化查询及连接池 | MIT |
| bcryptjs | 3.0.3 | bcrypt 随机盐口令散列 | BSD-3-Clause |
| typescript | 5.9.3 | 严格类型及架构 AST 检查，仅开发 | Apache-2.0 |
| @types/node | 26.6.6 | Node.js 类型，仅开发 | MIT |
| @types/pg | 8.23.1 | PostgreSQL 驱动类型，仅开发 | MIT |
| embedded-postgres | 18.6.0-beta.19 | 隔离真实 PostgreSQL 进程，仅测试 | MIT |
| @embedded-postgres/* | 18.6.0-beta.19 | 当前平台 PostgreSQL 二进制，仅测试 | Apache-2.0（分发）；PostgreSQL 及随附第三方许可证见包内 LICENSE.md |

首次安装记录：npm 安装审计报告 0 项已知漏洞。该结果仅针对当时依赖数据库，不能代替发布前扫描。
业务测试安装依赖后不访问外网；在线依赖漏洞检查使用 `npm audit --prefix backend --audit-level=high`，失败应阻断发布。
