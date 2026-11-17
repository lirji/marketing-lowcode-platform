# 控制台错误诊断

`POST /api/v1/client-errors` 是 Edge Gateway 内的诊断入口。前端 API 5xx、window error、unhandled rejection 和 React ErrorBoundary 会尽力上报。204 表示该实例已调用日志记录器并增加计数；不表示事件已持久留存、告警已发出或业务失败已恢复。

## 身份与隐私边界

- 复用现有 DEV/OIDC 身份链；OIDC 只接受验证后的 bearer token 与业务租户声明，DEV 只在明确启用开发认证时读取开发请求头。无需额外业务权限，不提供事件查询接口。
- 正文必须是最多1024字节的 UTF-8 JSON，包括空白；拒绝重复字段、尾随JSON、额外字段及类型强制转换。非法/超限正文分别返回400 `CLIENT_ERROR_INPUT_INVALID` / `CLIENT_ERROR_REQUEST_TOO_LARGE`。
- 固定字段为 `schemaVersion=1`、`source`、`route`、`httpStatus`，可选32位十六进制 `backendTraceId`。来源和模块是封闭枚举；无HTTP状态时传0，其他状态100–599。详见 `marketing-contracts/src/main/resources/openapi/marketing-api.yaml`。
- 不接收错误原文、堆栈、URL/查询串、令牌、操作者、客户端租户和业务ID。采集器自身的console.error只输出固定分类（浏览器及React自身错误输出不在此承诺内）。服务器日志的 `tenantHash` 从可信租户做SHA-256摘要，仅供关联，不是匿名化承诺，不进入指标标签。
- TraceId仅关联已有后端trace；未知或非32位十六进制关联ID省略，不制造追踪覆盖率。

## 容量与故障

- 前端每个页面生命周期每分钟最多10条，单条3秒截止；直接使用认证fetch/keepalive，失败静默、不重试、不递归，也不建立待发队列。DEMO、未建立身份或退出后不采集。
- 复用现有Redis集群令牌桶，独立租户键 `marketing:gateway:rate:{tenant}:client-errors`。默认每秒5条、突发10条，由 `MARKETING_GATEWAY_CLIENT_ERROR_RATE` / `MARKETING_GATEWAY_CLIENT_ERROR_BURST` 配置；沿用全局网关限流开关，正常部署必须保持启用。
- 配额耗尽429和 `Retry-After: 1`；Redis无结果或不可用503且拒绝采集。采集故障不改变原业务错误，不等于没有发生前端错误。
- 本机Compose只给edge-gateway设置Docker `local` 日志驱动、`max-size=10m`、`max-file=3`。容量轮转会淘汰旧日志，并不承诺保留天数；后续生产日志平台和留存期限仍需明确目标环境。
- 旧 `/client-error` 已退役为410，不再返回丢弃事件的假成功。没有幂等保证，主动重复发送会重复计数。

## 排障入口

在授权的运维环境通过 `docker logs --since 10m marketing-r1-edge-gateway-1` 查找 `client_error schemaVersion=1`。本地日志只供具备容器访问权的操作者查看，不向浏览器暴露。

Micrometer计数器为 `marketing.console.errors`，只有 `source`、`route`、`status_class` 三个固定集合标签；Prometheus命名为 `marketing_console_errors_total`。使用现有受保护的指标采集通道，不新开放Actuator权限。计数只含已接受的合法事件；限流/Redis故障沿用 `marketing.gateway.rate_limit.requests` 的 outcome 指标。日志缺失时先核对认证、浏览器预算、Redis准入及轮转，不把204当作长期存储证明。

此片没有新增数据库、中间件或生产部署；真实身份提供方、集中留存、告警阈值、完整追踪和生产容量测试仍属于后续生产验收。
