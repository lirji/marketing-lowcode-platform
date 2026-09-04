# 外部系统与 Provider Connector 指南

平台不重建商品、订单、会员、支付或商业触达系统。连接器通过防腐层把外部模型映射为平台 versioned contract；领域代码不能 import provider SDK 类型。

## Connector contract

实现 `ProviderConnector` 时必须声明 provider/type/version、支持的 channel/action、timeout、最大 payload、rate policy、idempotency 能力、callback verification 和 retry classification。请求包含 tenant、effect/contact idempotency key、template/version、tokenized recipient、expiry、trace 和签名；返回只能是明确 SUCCESS、REJECTED/FAILED 或 UNKNOWN。

```text
timeout / connection reset -> UNKNOWN -> query by idempotency key before retry
429 -> RETRYABLE with Retry-After, tenant/provider bulkhead
4xx validation/auth -> NON_RETRYABLE + alert/config remediation
5xx -> bounded exponential backoff + jitter, then DLQ
duplicate callback -> return accepted but do not transition twice
```

`SandboxProviderConnector` 用于本地和契约测试；`SignedHttpProviderConnector` 展示 HMAC 请求边界。生产实现应使用 mTLS/workload identity 或密钥平台注入 secret，禁止把 secret 放 Definition/Template。

## 安全要求

- endpoint 来自管理员登记的 allowlist，不接受运营输入任意 URL；解析 DNS 后仍阻止 loopback、link-local、metadata 和内网越权地址。
- HMAC 覆盖 method、canonical path/query、timestamp、nonce 和原始 body digest；callback 同样验签并限制时钟偏差。
- PII 最小化；日志只记录 provider、receipt、status、latency、tokenized subject 和错误类别。
- 每个 tenant/provider 独立并发、queue、circuit breaker 和 frequency/rate budget，防 noisy neighbor。
- Send/Grant 前最后一跳重新检查 consent/suppression/frequency/quiet hours，不相信早先入群结果。

## 验收套件

连接器发布前用 stub/WireMock 覆盖成功、业务拒绝、429、5xx、timeout、慢 body、断连、重复/乱序 callback、坏签名、clock skew、超大 payload、SSRF 和 key rotation。重复相同 effect key 最终只能有一个外部副作用；UNKNOWN 经查询收敛。压力测试必须证明 provider 变慢不会耗尽 Decision/Control 线程池。

## 商品/订单 ACL

商品 snapshot 包含 catalog version、价格基准/范围/asOf；订单请求用 OrderRef、Cart digest、line refs 和版本，不复制订单 aggregate。订单确认/退款均携带永久 commandId；部分退款引用原始 allocation，不用当前营销规则重算。外部契约 major 变化通过新 adapter 并行运行，shadow compare 后切换。
