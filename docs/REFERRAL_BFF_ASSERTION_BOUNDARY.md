# 裂变BFF断言接入边界

2026-09-08。依据referral-campaign-20260907/EXTERNAL_CONTRACTS的C01以及PRODUCTION_DESIGN；独立agent完成原平台源码核对。本文件约束后续接线，当前无BFF公开写入口、无真实来源签收。

## 现有基础与缺口

platform-web的OIDC认证链可复用，但默认JWT配置不自动提供C01专用issuer/audience/算法及JWKS超时合同。TenantContextFilter的TenantScope.actorId来自机器JWT sub，不是参与用户的canonicalSubject。已有DEV headers及组织owner回退不构成裂变用户授权；新入口必须显式拒绝DEV模式并同时校验机器身份、业务租户及组织店铺范围。

CanonicalMapCodec是长度前缀Map<String,String>编码，不是跨语言规范JSON；SignedTokenCodec是自定义Ed25519三段格式，不是JWT/JWS，不能直接声称满足BFF断言的签名声明。旧JVM幂等缓存不能承担跨实例jti消费。可复用底层加密/签名和持久幂等组件，但必须保留专用语义与持久业务唯一性。

## 最小实现切片

BffSubjectAssertionVerifier放在referral-service基础设施层，由未来参与Controller在事务外调用。机器访问身份与独立用户断言须同时通过；不接受body中的受益人、不将机器sub改名为用户主体。

验证器使用受控配置的固定issuer/JWKS、专用aud=marketing-referral和明确算法白名单；不从jku/x5u等请求值取钥。实际BFF来源、机器权限、算法及轮换/撤销策略待双方确定，缺配置不装配受理入口。令牌/请求大小、连接/读取超时须有界，并显式拒绝外层事务验签。

断言必需iss/aud/sub/tenantId/organizationId/shopId/iat/exp/jti/method/path/bodyDigest，字段类型严格；主体按精确值比较，最多256 Unicode字符，不截断或自动规范化为另一个身份。tenant/org/shop同时绑定机器允许范围、活动范围与实际请求。

method/path/bodyDigest绑定实际请求，不从业务body复写待签字字段。规范JSON算法必须作为显式版本合同，包含跨语言golden fixtures、未知字段、重复键、数字/Unicode等边界，不能用DTO反序列化后静默丢失字段的摘要替代。

有效窗口最多60秒，iat/exp/可选nbf都需校验；允许时钟偏差须显式配置并受冻结合同上限约束，不自动放宽。业务锁等待与主体加密后、写参与事实前重新检查时限及全部绑定。验签结果只保留最小必要值并脱敏toString；异常不回显令牌、主体或内部网络地址。

## 消费与永久原请求回放

同事务保存断言消费凭证及参与/绑定结果，唯一作用域至少tenant+issuer+jti；原用户、operation、业务幂等键和完整稳定请求摘要均绑定。相同断言身份不能换操作、主体或内容；原业务重试不得被当作新恶意请求。

失败事务不产生消费成功。成功后凭永久业务回执回放，不因短期命令缓存淘汰或断言过期改变历史成功；不能仅凭jti取结果，也不能给另一个主体返回参与资料。安全撤销与历史结果展示的具体权限区别待接口合同确认，不能擅自删除消费或资金审计证据。

原始canonicalSubject仅经可信身份映射/保护Port转换为分离版本HMAC索引和密文后持久化；HMAC不等同普通SHA摘要。加密/索引密钥分离与轮换、隐私保留期限继续按原设计和待确认条件处理。

## 验证与未完成

后续需要真实RSA/Ed25519（以签收算法为准）及本机JWKS密码学测试、HTTP机器+用户双身份集成、跨租户/店铺/主体/方法/路径/摘要篡改、DEV拒绝、超时及锁后过期、jti竞争/回滚、命令清理后原成功重放和日志净化。不能以内部Port替身或纯规则测试替代这些证据。

当前参与者持久化与规则编译可并行实施，但均不得据此开放真实BFF流量。真实权威主体来源、BFF服务身份、生产参数及完整裂变/生产验收仍待接通或确认。
