# QA Report

PASS（仅本纯合同切片）。隔离源码快照、Java21、Maven离线，只运行ReferralAwardAuthorizationCodecTest：10项，失败/错误/跳过0，session62081退出0。原始XML和文本报告同目录。

覆盖AC01–06：真实Ed25519与错钥、完整信任边界、未来/过期/超长寿命及锁后纯时限复检、未知/缺失/非规范声明、重签换钥摘要稳定、业务字段变更、角色/数量/关系、Unicode与诊断脱敏。4个源码/测试指纹与快照一致。

未访问数据库/网络/生产；不是当前全仓库通过。

统一sourceRequestId仅由格式域、tenantId和永久rewardId生成，Claims强制自洽。首次9项证据保存在initial-9；当前补强后10项通过，不相加为19项。
