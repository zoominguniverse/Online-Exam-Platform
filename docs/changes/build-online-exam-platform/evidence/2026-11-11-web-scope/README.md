# 当前 Web 范围 apply 验收

参考提交 c63f6e9de010493f8c4856189087a4fbf52ef4a6，分支 codex/build-online-exam-platform-7-2，未提交。范围为管理端、学生Web和Java后端。旧身份入口、适配器、专属客户端和Node替身测试已移除；V1–V13原样保留，历史凭据只做撤销/恢复兼容清理。当前技术门禁已不依赖外部客户端资料。

| 验证 | 结果 |
| --- | --- |
| Node / Java / Chromium | 3/3、28/28、9/9，零失败/跳过；Java21、PostgreSQL17.11、Node22.22.2、Chromium158.0.8127.18 |
| 契约 / 规格 / diff | 当前73个操作与Controller双向一致，30项Requirement/71项Scenario已映射，严格规格及diff通过 |
| Web入口 | 两个入口均显示真实API v1，实际阻断请求后重试成功；空字段失焦可见提示，合法输入后清除 |
| 页面可用性 | 管理23/学生15路由各测375/768/1440；32数据页延迟/断网后重试恢复；最小测量正文对比度7.138:1，可见键盘焦点3px |
| 当前身份 | Web独立会话并发开始/恢复/改密撤销；历史令牌即使配合合法Cookie也无法认证，旧接口403；历史凭据在改密/删除中清理 |
| JSON限制 | 实际HTTP覆盖Application/JSON及application/problem+json的2MiB上限，整体400 |
| 计分/授权变异 | 两项真实目标断言均拒绝错误实现，退出1；只改tmp副本 |
| 负向门禁 | 故意期望deliberately-invalid版本而服务返回v1，真实JUnit断言失败，脚本退出1；不是下载错误 |
| 容量 | 100会话稳定600秒，6000列表/12000保存/100交卷，成功率100%；p95 6.628/13.286/467.642ms，低于500/1000/1000ms预算 |
| 定时截止 | 额外100份自有测试答卷受控缩短截止，不调用HTTP补处理，worker最大延迟1573.764ms；总测量609.052秒 |
| 发布与恢复 | 真实c63f6e9旧API/Web回退可见原答案；在途交卷一次提交；一致性DB/附件恢复，较新删除清单重放及旧会话撤销；RPO 14.652s，数据恢复5954ms，含启动/登录/私有图片核对完整恢复15706ms |

完整流程由真实管理UI创编五题型、两任务卷，经学生Web提交/SELF批改、逐卷完成、错题/原图历史回看、消息首次阅读、资料头像及按requestId审计。图片为实际上传的96×48 PNG，公式、长表和答案为测试内容。日期改为2040及缩短测试答卷截止是明确的受控试验，未修改宿主时钟。

硬件、配额及数据量见[容量报告](capacity-report.json)：Windows宿主Ryzen7 9700X/16逻辑CPU/约16GB内存，Linux Docker；API2CPU/4GiB、DB2CPU/2GiB；103用户/202答卷。仅清理本轮自有随机资源，保留专属镜像和忽略的tmp备份。

[技术门禁](web-gate.json)、[发布预检](release-preflight.json)、[汇总](result-summary.json)、[完整Web明细](workflow-summary.json)、[入口明细](web-entry-summary.json)、[运维](operations-report.json)、[变异](mutation-report.json)、[负向控制](negative-control.json)、[待审阅任务](pending-task-review.json)。发布预检仅因36项任务尚未完成审阅而退出1；本轮遵循不擅自勾选的约束，OpenSpec保持17/53，不能把技术通过当作发布批准。

截图：[桌面结果](workflow-graded-review.png)、[375px结果](workflow-mobile-review.png)、[任务完成](workflow-task-completed.png)、[请求审计](workflow-audit-request.png)、[实际旧版Web原答案](previous-web-historical-answer.png)。成功截图已打开复核；不复制失败快照、密码、Cookie或令牌。旧证据目录保持原生成时间及范围，不改写成当前结果。

最终 Web 镜像补验：[实际发布/回退/恢复](final-release-report.json)，项目 exam-release-test-877c533fc3，完成于 2026-11-11T07:55:47.106Z。此轮包含最终表单/导航代码，重新构建 API/Web、实际旧版配对回退、在途交卷、备份/恢复/删除清单及断库恢复，全部通过；使用 --skip-capacity，不重复计算已独立完成的100会话容量。此次 RPO 14.263s，含启动/登录/原图核对恢复 14658ms。旧版浏览器截图见[最终旧版原答案](final-previous-web-historical-answer.png)。

倒计时证据来自独立可视 Chromium：关闭默认焦点模拟后实际切换标签，观察可信隐藏/显示事件，等待实际 GET 取时后断言显示19分段。只将页面日期设为2040，测试时钟在应用取样前安装，不改宿主时钟；详情见 workflow-summary.json 的 browserLifecycle。[原始浏览器结果](browser-results.json)含9项结果及耗时，零重试/失败/跳过。[迁移校验](migration-integrity.json)逐一比对本轮之前快照，V1–V13字节未改。
