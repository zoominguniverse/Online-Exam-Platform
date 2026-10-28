# Spec Delta

## Purpose

提供前后端和外部测试客户端可以稳定依赖的接口协议，统一资源命名、响应、错误、参数验证和查询规则，并使架构边界、幂等声明及配置错误能够在交付前被明确检查。

## ADDED Requirements

### Requirement: CT01 分层契约
后端 SHALL 遵循接口层到业务层到数据访问抽象的依赖方向，数据库查询仅在数据访问层实现，外部能力经最外侧适配器接入。交付门禁 SHALL 检查跨层直连、反向依赖和业务层数据库语句。

#### Scenario: CT01-F 架构违规
- **WHEN** 在业务模块引入直接 SQL 或接口层直连数据库
- **THEN** 架构检查非零退出并指出违反的依赖边，修复前不得合并

### Requirement: CT02 版本化资源命名
业务路径 SHALL 使用 `/api/v1/` 前缀和小写复数资源名，GET 只读、POST 创建、PUT／PATCH 更新、DELETE 删除，不以动词命名路径。状态操作 SHALL 表达为 submissions、gradings、publications 等业务子资源；契约改变须提供新版本和兼容说明。

#### Scenario: CT02-N 路由核对
- **WHEN** 静态核对文档与已注册路由
- **THEN** 提交使用 `POST /api/v1/attempts/{id}/submissions` 等资源路径，不存在 GET 修改数据或 `/submit` 动词接口

### Requirement: CT03 统一响应和错误码
所有应用 JSON 响应 SHALL 包含 `code,message,data,requestId`，成功码为 `OK`，失败 data 可包含字段错误但不含堆栈。错误清单 SHALL 分为参数 PARAM／400、认证 AUTHN／401、授权 AUTHZ／403、隐藏资源 RESOURCE／404、冲突 BIZ／409、依赖 DEP／503、限流 RATE／429、未预期 INTERNAL／500，并登记具体码。

#### Scenario: CT03-N 跨接口解析
- **WHEN** 客户端分别收到成功、参数失败、版本冲突与依赖不可达响应
- **THEN** 使用相同解析逻辑获取业务码与请求标识，HTTP 状态符合清单，信息包含可执行下一步

#### Scenario: CT03-F 未登记错误
- **WHEN** 新接口返回清单外业务码或无 requestId 的失败体
- **THEN** 契约门禁拒绝该接口通过验收

### Requirement: CT04 边界校验
接口边界 SHALL 白名单校验字段、类型、长度、范围和枚举，拒绝未声明字段，业务层只接收已校验 DTO。业务层 SHALL 另行检查归属、状态和跨记录不变量，不得以边界类型校验替代业务授权。

#### Scenario: CT04-F 非法输入早拒绝
- **WHEN** 客户端传缺失必填字段、错误类型、未知字段或超限答案
- **THEN** 返回 PARAM 分区字段错误，业务写入与外部调用均未执行

### Requirement: CT05 写接口幂等与条件更新
每个写接口 SHALL 声明幂等策略。非自然幂等创建或业务动作须有 `Idempotency-Key`，按主体、方法、路径和键隔离并校验请求摘要；同键异载荷返回冲突。更新须携带预期版本，重试已成功操作返回原结果；存储唯一约束 SHALL 兜底重复副作用。

#### Scenario: CT05-N 重复请求
- **WHEN** 同一主体并发发送相同键和载荷的提交或消息请求
- **THEN** 仅一次副作用且返回同一资源结果；其他主体不能读取该缓存结果

#### Scenario: CT05-F 键冲突或过期版本
- **WHEN** 重用幂等键但改变内容，或以旧版本更新同一资源
- **THEN** 返回 BIZ_IDEMPOTENCY_CONFLICT 或 BIZ_VERSION_CONFLICT，不覆盖已有结果

### Requirement: CT06 分页排序筛选
列表 SHALL 统一 `page=1,pageSize=20`，pageSize 最大 100，超过上限截为 100；非正整数拒绝。排序和筛选 SHALL 按每资源白名单校验，排序不唯一追加 id 同向兜底；非法字段、类型或取值不得忽略，不得退化为全量查询。

#### Scenario: CT06-N 稳定分页
- **WHEN** 在固定数据集构造相同创建时间记录，依次查询第一页和第二页
- **THEN** 以创建时间和唯一 id 稳定排序，无重复遗漏，响应含 items、page、pageSize、total

#### Scenario: CT06-F 非法筛选
- **WHEN** 查询传不存在的筛选字段、越界年级、非法排序字段或 pageSize 为负数
- **THEN** 返回明确 PARAM 错误且不返回任何全量列表

### Requirement: CT07 配置外置与启动失败
地址、密钥、环境差异和特性开关 SHALL 只从环境变量注入，配置样例只列键名，缺失必需变量或格式非法 SHALL 使进程启动失败。已明确的业务常量可版本化登记，不得伪装成环境配置默认值。

#### Scenario: CT07-F 缺失配置
- **WHEN** 删除 DATABASE_URL、允许来源或令牌签名必需配置后启动
- **THEN** 非零退出，提示缺失键名但不输出配置值，不连接默认数据库也不进入就绪态
