# Delivery Report

## 交付

内部邀请token发行与解析已实现：256位随机opaque token、永久hash身份、专用AEAD/KMS保护Port密文回执、本人/Scope/版本锚点、历史冻结发布许可、同key原响应回放、显式≤24h回显窗口、可信最小摘要及事务外来源调用。默认配置回显窗口0及所有新增来源Port拒绝。没有HTTP、绑定、奖励或生产接入。

V2新增mk_referral_invite_token和mk_referral_invite_replay，两表及全部字段中文注释；V1迁移和参与生产语义未改。token过期/撤销和回显窗口过期均保留原hash及请求冲突身份。密文物理保留/删除期限尚待隐私治理；没有自动清理Worker，不能声称已在24h删除数据。

## 验证

初轮15项通过（12项MySQL令牌、2项纯AEAD、1项旧schema兼容）；独立审查修复后1项摘要水位专项已通过、退出0。不是整个平台全量通过。日志及原始Surefire证据均在本目录。

## CI/交付状态

新服务已通过前一切片注册Maven reactor，现有根CI自动纳入；本轮不新增权限或发布步骤，未执行远程CI。仅离线临时快照定向构建，未写共享target、install、提交、推送、部署或共享数据库。

后续首绑见BINDING_HANDOFF.md。真实KMS/BFF签名断言/历史release/公开摘要、生产环境参数、隐私物理清理、maxBindAge起点仍单独确认验收。返回原过期token的幂等成功只表示原发行响应恢复，不重新赋予该token有效性；解析/未来绑定仍必须当前校验。

最终证据：evidence/summary-expiry-1仅收集本次新增用例报告；evidence/source-digests.json核对29个源码/配置文件与最终被测快照一致。两次构建句柄均退出0，无运行任务。单测容器启动耗时较高，后续无DB逻辑优先纯应用测试，真实DDL/并发/回滚仍保留隔离MySQL。
