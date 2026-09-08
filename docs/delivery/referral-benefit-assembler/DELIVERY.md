# 裂变奖励候选组装结果

## 已实现范围

新ReferralAwardIntentAssembler只输出CANDIDATE_ONLY：固定marketing-referral来源、Identity稳定sourceRequest、单COUPON quantity1、expectedSkuVersion。输入不能覆盖主体/SKU/金额。机器Scope许可为referral:award，仅CENTER；真实授权信任和历史发布/目录ProofPort默认拒绝。未新增HTTP/资格确认/风控/持久受理/Outbox/发送。

信任结果只来自注入的固定服务配置，key查找限定不可变公钥集合，issuer和最大token/proof寿命无生产默认值。Proof须由未来可信适配器验证发布签名、artifact attestation、ABI/hash、完整发布scope、永久奖励角色受益主体、原opaque引用与目录实体映射。完整稳定claims摘要绑定participant/relation或milestone/subject/revision等全部声明，不能只echo输入或将record当密码学证明。当前没有真实适配器，不宣称已验证真实发布或目录。

完整ReferralPlan使用同一个SPI Validator，按唯一ruleId核对角色、模式、阶梯门槛和数量。目录以原benefit/SKU opaque引用逐字比较，并返回typed benefitId/version和skuId/version/type。没有规定引用字符串格式；真实映射和来源仍待签收。选中阶梯并不证明人数达标，仍须在线资格确认。

全部外部端口在事务外；完成读取后用原始Instant复检授权和证明有效期。候选及意图toString脱敏，异常不携带原token、主体或适配器内部异常链。旧Assembler/Service及drools-activity来源没有改变；新候选独立DTO不接旧relay。

## 专项验证

2026-09-08 11:26:43 +08:00，独立临时源码快照离线执行：
`mvn -o -pl services/benefit-funding-service -am -Dtest=ReferralAwardIntentAssemblerTest,AwardDispatchModeRouterTest,ReferralAwardAuthorizationCodecTest -Dsurefire.failIfNoSpecifiedTests=false test`。

20项JUnit通过：新候选9、原路由1、共享真实Ed25519合同10，失败/错误/跳过均0。覆盖真实签名、重签稳定内容、错误来源/Scope/签名、目录/制品/角色/阶梯/重复规则、纳秒过期、默认拒绝/外层事务和旧DTO无新字段。没有DB或服务启动，无全仓库全量。日志maven-pure.txt、Surefire和12个源码/依赖SHA证据同目录；旧Assembler/Service当前SHA也与HEAD相同。

## 待完成门禁

独立最终审查已通过本纯组装阶段；12当前源码SHA与测试快照一致，20纯组合日志已核对。在线qualification confirm、风险评估、永久幂等/准备/receipt、取消竞争与恢复、可信relay身份、权益中心真实受理和履约均不在本切片内。候选不是发奖授权已完成，不是生产验收。真实渠道、权限映射、生产有效期配置和隐私方案仍待确认。
