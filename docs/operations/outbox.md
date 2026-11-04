# Outbox 与到期扫描

业务事务只写 outbox_events，不做 HTTP、文件或通知调用。`npm run worker --prefix backend` 启动独立进程，每轮先扫描最多 100 份到期答卷，再在 OUTBOX_ENABLED=true 时领取一条事件。关闭该开关只暂停派生事件投递，不暂停答卷截止约束；重启 worker 无需重新提交答卷。

领取使用单条短事务语句和 FOR UPDATE SKIP LOCKED，租约 30 秒，执行在领取提交后进行。每次领取生成新的 lease_token，只有当前凭证可以确认或修改状态，过期领取者不能确认新租约。消费者执行时间应短于租约，长任务接入时须提供续租；本期内置消费者仅执行本地短事务。

正常状态：pending → leased → completed。失败后按 1/2/4/8/16 秒退避，最多 5 次重试（首次加 5 次重试）；仍失败进入 failed 队列，输出含事件 ID、责任动作的 OUTBOX_RETRIES_EXHAUSTED 单行 JSON 告警，不记录异常原文或事件正文。attempts 表示已安排重试次数，最大 5。进程在外部执行后、确认前退出时，租约到期重新领取；不承诺外部系统恰好一次。

当前仅注册 attempt.submitted、attempt.graded 的内部消费者。内部消费者在同一数据库事务写 event_receipts 唯一事件 ID 和派生活动审计，领取重投不会重复写效果。未来外部通知或 AI 消费者必须按 event.id 做独立去重，并将去重记录与外部效果在其一侧原子提交；不能依赖生产者的确认就宣称无重复。

## 故障处理

1. 按事件 ID 查询 status、attempts、lease_until、last_error；不要输出 payload 正文。
2. leased 未到期时先排查工作进程，避免手工抢占正在执行的任务；死亡进程的租约到期可自动恢复。
3. failed 时先修复消费者或依赖，然后通过受控离线环境注入 EVENT_ID 并执行 `npm run outbox:replay --prefix backend`。命令只接受 failed 事件，重置预算并写系统审计，保留 event ID 供消费者继续去重。CLI 不绕过资源的公开权限。
4. 若外部目标不支持幂等，不直接重放，先核对目标侧效果并制定补偿；当前无外部目标。

GET 答卷详情和列表仅计算有效状态，不写事件或审计；后台或写请求持久化冻结。后台停止期间已到期答卷仍显示待批改，新的答案不可写入。成绩确认与错题、审计和派生事件同事务提交，任一步失败全部回滚。
