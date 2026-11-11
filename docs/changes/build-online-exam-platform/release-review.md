# 发布评审材料

本次评审对象为build-online-exam-platform的管理端、学生Web和共用Java后端。范围内实现和验证材料见[收尾记录](completion-acceptance.md)及[最新证据](evidence/2026-11-11-completion/README.md)。分支codex/build-online-exam-platform-7-2，参考提交c63f6e9de010493f8c4856189087a4fbf52ef4a6，工作区变更尚未提交；该参考提交本身不包含本轮修改，发布前应固定实际评审通过的提交/产物。

Node22.22.2、Java21、PostgreSQL17.11，锁文件/镜像摘要及许可证见[依赖](dependencies.md)。API73个操作见[OpenAPI](openapi.yaml)；[数据字典](data-dictionary.md)、[场景索引](scenarios.md)、[运行/恢复手册](operations.md)均已同步。已有演示仍保持独立，浏览器本地演示成绩不能导入权威成绩。

技术验证为Node3/Java28/浏览器10、45入口三宽度、35数据页失败恢复、16空视图、14组写失败/重复抑制、真实旧版配对回退、数据/附件恢复及既有同后端容量与两项变异。主观题显示SELF，离线未同步且跨过截止的内容不补写，历史版本和原图保留。

发布前的评审事项：依赖审计已记录6个Maven组件22条公告及npm中危/低危公告，版本按确认的锁定基线保留，需判断适用性和升级方案；生产HTTPS/域名、外部机密、值班人员和备份定时服务尚需部署方配置。本轮没有部署线上环境、安装生产备份定时器、创建提交、推送、合并或归档。旧五项规划的重复能力整理与主规格同步按后续明确授权处理。
