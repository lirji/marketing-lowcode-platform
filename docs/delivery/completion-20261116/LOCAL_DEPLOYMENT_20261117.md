# 本地 Docker 更新回执（2026-11-17）

## 交付与目标
- 产品提交：`045dfd933f878562b611c83b7e555cd74e720a82`，S12b精确CI `44032496693` 三项PASS，正常快进推送main；S11d/S12a亦已分别通过精确CI。
- 目标：原目录中的本机 `marketing-r1`，11个已有应用；控制台 `http://127.0.0.1:8084`、网关 `http://127.0.0.1:18080`、Flink `http://127.0.0.1:18081`。不涉及生产、其它项目或Flink作业重新提交。
- 全部新镜像以不可变image ID运行，并标记上述源码提交。沿用已验证的宿主机构建产物+既有运行镜像方法；manifest保存各JAR/控制台产物hash及新旧image ID。

## 配置与恢复
- 更新前已备份5个将迁移的业务库，保留全部旧镜像。备份成功不代表完成恢复演练或达到RTO/RPO。
- 逐服务 `up -d --no-deps --no-build`，每个服务健康后再继续。现有环境值、监听地址/端口、内存上限及数据卷经过预检；控制台显式保留8084。
- 首次触达服务更新后Docker socket短暂不可用，按预案恢复该服务旧镜像并确认健康；核对实际状态后恢复执行，其余成功服务未重复更新。最终11服务全部新镜像、healthy、无重启或OOM。
- 真实浏览器暴露旧S03挂载配置遮蔽DMN及履约详情路由。已确认挂载文件等于3323e48的网关源码，新JAR含同样设置及这两个新增路径；将网关 `SPRING_CONFIG_ADDITIONAL_LOCATION` 设为空并单独更新网关。原挂载文件保留，其它配置不变。更新后两个路由均通过。
- 履约观察器仍按现有默认关闭；没有对外发奖、真实短信、审批激活或重算业务数据。

## 验证结果
- 11应用镜像/版本/健康检查PASS；6个共享dev-infra容器ID及启动时间不变；原先暂停的17个langchain4j应用保持停止。
- 原3个Flink作业ID保持RUNNING，完成检查点数分别从3436→3453、3435→3451、3437→3453。没有重复提交任务。
- Flyway历史全部成功：control V5、measurement V5、decision V3、benefit V13、engagement V5。
- 实际8084控制台，桌面和Pixel7两视口：真实活动筛选、报告读取/CSV下载、真实DMN分析及VIP求值通过。无API替身、无页面错误、无横向页面溢出；桌面分析页及手机DMN截图已实际查看。
- 实际18080：合法已批准Journey `rcn-journey-sms` 仿真完成，SEND仅预览，控制命令、Outbox计数和定义版本/状态不变；活动筛选非法参数稳定错误码、未知活动零结果、履约详情子路由均通过。
- 实际分析水位仍停留在2026-09-16。页面明确提示尚未覆盖当前查询窗口，零值不代表完整业务结论；未用测试事实污染共享库。
- 旧演示Journey `mock-journey-cart-recall` 的 `journey.wait_timer` 历史节点不符合当前注册表，因此仿真拒绝；未覆盖原版本。此项进入S12种子迁移待办，不能将本次合法Journey验收解释为所有历史定义已迁移。

## 证据与恢复入口
证据目录：`~/.local/share/marketing-delivery/completion-20261116/runtime-045dfd9/`。
- `artifact-manifest.json`、`rollout-state.json`、`rollout-attempt1.json`：镜像来源与逐服务结果。
- `runtime-smoke.json`、`api-smoke.json`、`browser-smoke.json`：实际验收；`actual-*-analytics.png`、`actual-*-dmn.png`与CSV。
- `gateway-config-reconciliation.json`：旧配置停用依据。
- `compose-command.json`、`prepare-rollout.py`：当前Compose文件组合及必要非敏感端口覆盖；Redis插值从已有网关配置读取，不输出值。
- `containers-before.private.json`、`rollout.compose.private.json`、`database-backup.sql.gz`及私密日志仅存本机0600，不进入Git。

恢复时先查询实际容器、镜像、健康及Flink作业，再读取本目录PROGRESS。只回滚明确失败的应用，不自动回滚数据库、恢复langchain4j或重复submit。任务分支及证据/备份保留；未建立新worktree，也未清理用户文件。
