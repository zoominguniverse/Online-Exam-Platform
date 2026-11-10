# 构建依赖与审计

2026-11-10，在干净的 Linux arm64 容器中验证。Java 的构建和运行镜像以及 PostgreSQL 镜像在配置中固定到摘要。

| 范围 | 实测版本 |
| --- | --- |
| 构建 | Temurin 21.0.10、Maven 3.9.12、Node 22.22.2、npm 10.9.7 |
| 后端直接依赖 | Spring Boot 3.5.16、MyBatis Starter 3.0.5；Boot 管理 Spring Security 6.5.11、Flyway 11.7.2、PostgreSQL JDBC 42.7.11；题库增加 jsoup 1.24.1 和 TwelveMonkeys ImageIO WebP 3.15.3 |
| 运行 | Temurin 21.0.11、PostgreSQL 17.11 |
| Web | Vue 3.5.45、Vue Router 4.6.4、Pinia 3.0.4、Element Plus 2.14.8、KaTeX 0.16.47、ECharts 5.6.0、TypeScript 5.9.3、Vite 7.3.8、Vue 插件 6.0.10 |

MyBatis Starter 3.0.5 以 Spring Boot 3.5 为基线。[MyBatis 发布说明](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-3.0.5)

Java 的 `mvn package` 构建成功。[解析后的 Maven 依赖树](maven-dependencies.txt) 记录应用的编译和运行解析结果，另以 [构建产物内的 JAR](maven-runtime-jars.txt) 核对实际打包组件。CycloneDX 2.9.1 生成的清单记录了 Apache-2.0、MIT、BSD-2-Clause、CC0-1.0 及部分组件的多许可证声明，其中 Logback 声明 EPL-2.0 / LGPL-2.1-only，Jakarta Annotations 声明 EPL-2.0 / GPL-2.0-with-classpath-exception。题库新增的 jsoup 使用 MIT，TwelveMonkeys ImageIO 及其公共模块使用 BSD-3-Clause。基础有效 POM 与许可证清单生成于 `backend/target/foundation-inventory/`。

Web 使用 `web/package-lock.json` 执行 `npm ci`，安装 76 个包。实装包声明 67 个 MIT、2 个 Apache-2.0、1 个 BSD-2-Clause、4 个 BSD-3-Clause、1 个 ISC、1 个 0BSD。Node 验证镜像为 `node:22.22.2-bookworm-slim@sha256:9f6d5975c7dca860947d3915877f85607946403fc55349f39b4bc3688448bb6e`。

## 已知安全公告

| 检查 | 实测结果 |
| --- | --- |
| Maven 编译和运行依赖 | 用 [OSV 批量接口](https://google.github.io/osv.dev/post-v1-querybatch/) 核对 58 个实际打包的坐标，6 个组件命中共 22 条公告，详见 [OSV 查询记录](maven-osv.json) |
| npm 锁文件 | `npm audit --json` 返回 1 个中危、1 个低危、0 个高危和严重项，退出码为 1。见 [ECharts 公告](https://github.com/advisories/GHSA-fgmj-fm8m-jvvx) 与 [KaTeX 公告](https://github.com/advisories/GHSA-238p-pmpm-9mq7) |

检查记录限于应用依赖。当前版本按已确认的规约锁定，公告的实际适用性及升级需在发布评审前处理。Spring Boot 3.5.16 是 3.5 系列最后一个开源维护版本。[Spring 发布说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)
