# Delivery Report

本切片交付类型化图编译与签名，保持旧方言，并主动关闭尚未接入的控制面新方言路径。原图字符串配置严格转换，复用 SPI 业务规则，不生成实际奖励。独立审查发现的语义hash歧义与JSON类型强制转换已修复，证据见 REVIEW_REPORT/QA_REPORT。

新产物 envelope：`{definitionId,scope:{organizationId,shopId},binding:{attribution,maxBindAgeSeconds,inviteeScope},policy:ReferralPlan}`；ABI 为 `marketing-referral-plan/1`。definitionVersion 由 ArtifactBundle 的签名范围携带。奖励版本 String 保留完整不可变引用，真实目录确认尚待完成。

新方言 sourceDigest 使用 `marketing-referral-graph-semantic/1` 域、UTF-8字节长度、集合数和固定顺序；精确字符串不做 NFC 合并，非法 Unicode 代理项拒绝；annotations 不承载业务且不参与摘要，节点 config 全部参与。旧方言原协议字节摘要不变，不修改历史签名或摘要。

CI沿用现有配置；只本地离线隔离专项，没有全仓库重复测试、迁移、启动服务、提交、推送或生产发布。回滚为代码模块/目录接入回退，不删除业务数据。完整服务持久化、控制面治理/仿真/发布闭包、隐私、权威合同与生产参数依旧是后续门禁。


2026-09-08 独立最终复核：compiler本阶段 pass；enum旁路关闭、源文件SHA匹配。仅本编译切片完成，不表示整平台或生产通过。后续机械共享parser重定位在referral-control-preview阶段独立记录。
