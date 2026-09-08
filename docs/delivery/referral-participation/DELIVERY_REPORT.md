# Delivery Report

## Outcome

原平台新增referral-service内部参与者持久化切片，加入成功永久固定规则/Scope，原成功同key/新key回放不随release升级改变；首次加入的身份、release/kill-switch、数据库索引版本锚点任一缺失均失败关闭。默认OIDC、DEV headers=false，尚无公开HTTP写入口或生产可信来源。

## Verification

初版12项隔离MySQL测试全通过（evidence/pre-anchor-12），独立审查后持久索引版本锚点/身份锁后时间修复的8项受影响专项全通过（evidence/final-anchor-8）。类共15个场景，未将12+8误称当前全部20个独立测试或整个平台全量通过。源文件摘要核对16个源码/配置文件与最终被测临时快照一致（evidence/source-digests.json）。真实容器验证唯一键、多线程竞争、角色锁等待、回滚、全6表字段注释，不使用共享库或纯内存正式存储。

## Changes

- services/pom.xml追加模块；新服务主类/安全默认配置、Participant、受信身份及参与许可Port、服务、Repository、MyBatis Mapper/XML。
- V1新建6表：participant、subject_role、join_command、audit、outbox、subject_index_anchor。无改动旧服务迁移、无部署数据库参数或种子真实身份。
- 同事务审计/内部Outbox仅含去主体化参与快照。没有relay、topic部署、消费者签收、token、关系绑定或奖励实现。
- 文档见本目录计划、状态、审查、QA及日志。根进度与任务档案由主任务统一维护。

## CI

现有.github/workflows/ci.yml运行根clean verify，security.yml运行根package；services/pom注册后自动纳入现有Maven reactor。未改CI权限或推送。本次只在隔离后端源码快照离线构建受影响模块，不宣称远程CI成功。

## Remaining Gates

真实BFF签名断言/JWKS/KMS、永久jti消费、规范JSON/真实method/path绑定、主体权威映射与加密/HMAC来源、受控索引锚点初始化及轮换方案、签名release/kill-switch接收、实际DB/环境/cell/Scope参数、保留期均未签收。应用不开放锚点创建/更新；只有测试夹具创建测试租户锚点。未来来源未落实前保持默认拒绝，不能用配置改HMAC版本绕过持久锚点。

此交付仅内部参与切片。完整token/绑定/资格/奖励/追回/规则发布/前端及外部生产门禁仍按原161项目标后续推进。回滚时保留已创建审计、参与/命令与Outbox，先关闭新参与，不删除数据。未提交、推送、迁移共享数据库或部署。
