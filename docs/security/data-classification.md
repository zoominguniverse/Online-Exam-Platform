# 数据分类与输出规则（唯一源）

| 类别 | 字段／内容 | 允许输出 | 日志规则 |
| --- | --- | --- | --- |
| 公开 | 静态说明、健康状态 | 对应公开端点 | 可记录 |
| 内部 | 学科、试卷、题目结构、任务 | 按角色及发布资格；标准答案除外 | 仅标识和动作，不记正文 |
| 敏感 | snapshot.referenceAnswer、explanation、答卷答案、逐题评分、错题 | 仅本人合法业务响应；作答中去掉参考答案和解析；到期按有效状态开放 | 永不记录正文或分数全文 |
| 个人信息 | username、grade_id、owner_id、姓名、手机号、头像 | 本人及明确管理接口的必要字段 | 通用日志省略姓名手机号；必要关联仅使用内部 UUID |
| 机密 | password_hash、ACCESS_TOKEN_SECRET、csrf_hash、token_hash | 永不响应 | 永不记录 |
| 机密：发行例外 | accessToken、刷新 Cookie、csrfToken | 仅认证成功向本人发行；刷新令牌只在安全 Cookie | 永不记录；请求日志不记 Cookie、Authorization 或 body |

普通业务响应通过显式 DTO 选取字段，不直接返回 users/sessions/idempotency_records 数据库行。账号列表不返回密码散列或认证版本。审计只记录 actor UUID、object UUID、动作、request_id 和时间，不记录输入全文。

手机号未来接入时默认前三后四位、中间掩码；本人编辑资料页的必要原值显示须按业务授权。标准答案和答卷为明确的本人业务例外，不得因此写入通用日志。测试只使用合成数据；不复制生产数据库。
