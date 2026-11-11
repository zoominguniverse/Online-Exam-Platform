# Web范围收尾验收

当前分支 codex/build-online-exam-platform-7-2，参考HEAD c63f6e9de010493f8c4856189087a4fbf52ef4a6，所有变更未提交。任务 53/53，完整平台门禁已通过，未进行生产发布、推送、合并或归档。

| 验证 | 实际结果 |
| --- | --- |
| 一键技术门禁 | Node3/3、Java28/28、Chromium10/10；零失败/跳过/重试；双入口构建、严格规格和diff通过 |
| 必需证据检查 | 10个真实浏览器场景必须存在且通过，独立校验公式、时段、双会话、资料和界面记录 |
| 路由/布局 | 管理28/学生17个入口含规范路径，各测375/768/1440；35数据页失败/重试恢复 |
| 表单/空视图 | 16类空视图；逐页标签/原生字段校验；14组受控写失败验证处理中仅一次请求与安全输入保留；密码清空 |
| 考试键盘 | 只用Tab/Space/Enter完成选项、准备交卷与确认，最终成绩1.00；另有真实可视浏览器后台计时同步 |
| 公式 | 管理预览与学生端分别断言分式mfrac、上下标msubsup和矩阵mtable；TeX原文一致 |
| 时段/任务 | 实时构造未开放/开放/已结束窗口，UI与API一致、关闭时开始409；后注册非目标学生API404、页面无进入按钮 |
| 消息 | A、B、A群发仅两份；A两个独立Web会话四次阅读，已读人数保持1，B仍未读 |
| 资料 | 学生头像/姓名/手机/生日在另一会话和重新登录后相同；管理员改手机；真实createdAt均不变 |
| 场景/负向控制 | 30项Requirement/71项Scenario逐一核对测试引用；缺必需报告/将双会话改为单会话均退出1；计分及归属真实代码变异此前均被断言拒绝 |
| 最终镜像演练 | exam-release-test-ce81db3820实际构建最终Web/API，真实c63f6e9回退及原答案、在途提交、DB/附件备份恢复、删除清单/撤销与断库恢复通过；RPO 15.034s，完整恢复14613ms |
| 容量 | 沿用同后端100会话600秒、6000列表/12000保存/100交卷，成功率100%；p95 6.628/13.286/467.642ms；额外100份定时截止最大延迟1573.764ms |

本轮仅修改Web、测试工具和文档，后端78个源文件/配置与已完成容量/变异时的副本SHA-256一致，含V1–V13。最终镜像重新执行发布恢复时使用--skip-capacity；没有重复声称跑过600秒容量。所有测试使用随机专属项目与测试数据，只清理自己的资源。

空视图中，学生动态通过删除该隔离库中本测试学生自己的审计行构造空历史；并非模拟接口。最后管理员不存在、详情对象为空、认证账号无资料等不满足业务不变量的空状态不伪造，分别检查正常、找不到对象或空可选字段。时段窗口远离边界以避免测试时间竞争，精确左闭右开另由真实PostgreSQL接口测试的可注入服务端时钟覆盖。

[汇总](result-summary.json)、[Web门禁](web-gate.json)、[浏览器完整结果](results.json)、[新增验收](acceptance-summary.json)、[完整流程/字段/布局](workflow-summary.json)、[发布恢复](release-recovery-report.json)、[容量](capacity-report.json)、[后端完整性](backend-integrity.json)、[场景覆盖](coverage-report.json)、[缺失证据控制](missing-evidence-controls.json)、[计分/授权变异](mutation-report.json)、[逐项勾选记录](task-progress-journal.json)。[发布评审材料](../../release-review.md)。

截图：[桌面结果](workflow-graded-review.png)、[375px结果](workflow-mobile-review.png)、[完成任务](workflow-task-completed.png)、[原版本答案](previous-web-historical-answer.png)。均为合成测试内容，不含凭据。旧证据及任务复核按当时进度保留，当前交付见[收尾记录](../../completion-acceptance.md)。

最终复核：成功桌面与375px截图已打开确认；当前文档本地链接均存在。只读核验12个本轮自有项目，容器/卷均无残留，见[清理核验](cleanup-check.json)和[最终复核](final-review.json)。
