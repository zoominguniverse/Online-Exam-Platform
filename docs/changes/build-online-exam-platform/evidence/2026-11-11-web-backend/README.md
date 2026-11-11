# 2026-11-11 Web 与共用后端验收证据

基线 `c63f6e9de010493f8c4856189087a4fbf52ef4a6`；分支 `codex/build-online-exam-platform-7-2`，未提交。Node v22.22.2、Java21、PostgreSQL17.11、Playwright1.65.0、Chromium 158.0.8127.18。最终 [Web门禁](web-gate.json) 通过；[平台门禁](platform-gate.json) 预期失败，缺真实微信证据且40项任务尚未勾选。没有把后端微信替身算成体验版。

| 检查 | 结果 |
| --- | --- |
| 全量 Node / Java / Chromium | 5/5、29/29、8/8；零失败/跳过 |
| 契约与规格 | 全部77个Controller操作、31项Requirement/74项Scenario映射；严格OpenSpec与diff检查通过 |
| Web 页面 | 管理23、学生15个路由，375/768/1440三宽度；作答页另测三宽度。32个数据页实际阻断/延迟请求后重试恢复 |
| 可达性 | 键盘登录/作答、3px可见焦点；正文计算最小对比度7.138:1。测量可见标题、段落、链接、标签、非禁用按钮和表格文字；不将禁用控件/图片当正文测量 |
| 丢失响应 | Java已提交后中断浏览器响应；交卷/批改/消息均用相同键和相同载荷重试并获得唯一结果 |
| 容量 | 100会话稳定600秒；6000列表、12000保存、100交卷，成功率100%；p95 5.581 / 11.471 / 335.422ms |
| 批量截止 | 稳定窗口后额外100份自有答卷受控缩短截止；不经HTTP补处理，worker最大冻结延迟1600.191ms |
| 发布/恢复 | 真正c63f6e9 API+Web配对回退及原答案；RPO 12.231s，数据恢复5758ms，含启动/登录/图片核对完整恢复15702ms |
| 关键变异 | 多选恒真、移除owner过滤均被真实目标断言拒绝，两个退出码1；仅改tmp副本 |

完整流程由真实管理UI创建五题型和两张任务卷，学生Web提交、SELF批改、两卷完成、错题和历史原图回看；管理查分、消息首次阅读、资料头像和请求审计均经同一Java/PostgreSQL链路。测试图片为实际上传的96×48 PNG色块，公式及表格为测试内容。客户端日期改为2040以及自有答卷截止缩短均是明确的受控试验，不能算微信真机或真实机房故障。

最终 Java测试包含错误逐题结果/末尾错题写入失败的整体回滚、同评分重复确认/终态改分409，以及真实exam_app无DDL权限的全链路和删除清单触发器。原六项保存测试继续包含真实离线、双会话冲突、账号隔离、API停机重启及直接数据库摘要。见 [保存摘要](summary.json)、[完整流程/路由/对比度/恢复明细](workflow-summary.json)、[汇总](result-summary.json)、[容量](capacity-report.json)、[运维](operations-report.json)、[变异](mutation-report.json)。

截图：[自行批改后的原题与分数](workflow-graded-review.png)、[375px结果页](workflow-mobile-review.png)、[两卷任务完成](workflow-task-completed.png)、[按requestId关联交卷审计](workflow-audit-request.png)、[真实旧版Web历史答案](previous-web-historical-answer.png)。已实际打开最终截图复核；长表格在内容区滚动。

日志、原始备份和失败排错产物留在忽略的tmp；本目录仅保留成功摘要和测试截图，不复制密码、Cookie或令牌。最终门禁期间Docker引擎曾停止，恢复后容器JVM到Maven Central的TLS异常；使用临时宿主传输到同一官方HTTPS源，未关闭证书/校验，代理随门禁结束关闭。脚本支持同样的可选本地传输。测试仅清理自身随机项目和卷，保留专属镜像，不清理共享资源。
