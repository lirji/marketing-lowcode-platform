# 商城会员画像接入

## 数据来源与授权

商城会员域拥有在籍状态和会员等级。营销首次接入 `member.level`，圈选对象为商城当前 `ACTIVE` 会员。该字段对应 `member_record.member_level`，不采集姓名、联系方式、登录主体或订单明细。商城内部 API 使用独立高熵 Bearer 凭据和显式租户配对，只能读取一个配置的商城租户。营销只为配对的营销租户呈现和使用来源。跨租户、缺配置、错误凭据、上游超时和非法响应均拒绝。凭据通过环境密钥装配，外部运行要求 TLS，本地可使用明确配置的 Docker 内部网络。引用是 `commerce-member:<商城会员ID>`，仅在服务间流转，营销快照只保存摘要。

## 一致性与操作

商城在同一只读事务内分批读取在籍会员和服务器时间。默认快照最多 10,000 人，超过上限拒绝整次请求。营销按已保存规则版本计算，只有明确映射的字段和类型可以参与真实圈选。快照在创建时重新读取完整画像并计算，可能随来源变化。采用现有数据库幂等命令和成员存储，短期快照固定规则与读取时间。源正常时允许预估和保存，源关闭或访问失败时保留规则编辑。旧样本预览和显式导入兼容。

控制台携带 `allowLargePopulation=true` 请求预估，营销向同一商城机器路径发送 `GET /internal/v1/member-profiles/snapshot?view=level-summary`。新版商城返回 `asOf` 和 `levels`，每项为 `memberLevel` 与正整数 `memberCount`。同一租户最多 1,000 个等级值，按二进制等值分组以保留大小写和空格，依旧使用三秒查询预算及原机器、租户限制。营销按原规则求和，校验来源时间、组唯一性及 32 位有符号人数上限，只向运营返回人数和观测时间。旧商城忽略该参数并返回原同步快照，营销在原上限内处理。未携带新参数的营销预估和所有同步快照使用原读取方式与上限。预估不持久化名单，也不作为事件水位。

## 发布范围

先交付并启用本机商城来源、营销适配和控制台，使用明确配对的本地演示租户。现有决策投影与该快照采用不同契约。真实在线资格要求决策调用方的可验证主体绑定、成员更新及撤销传播和独立联调，不能通过开启现有 Kafka 消费器代替。

## 验收

验证真实商城 MySQL 中的会员等级、冻结会员排除、商城机器鉴权和营销租户隔离。验证已保存规则的真实人数、完整快照、重试幂等、关闭及超时路径和控制台。商城先完成功能交付，营销默认关闭来源以支持滚动部署。本机验收通过后按环境启用。

## 环境配置

- 商城启用 `COMMERCE_MARKETING_MEMBER_PROFILE_ENABLED`，配置 `COMMERCE_MARKETING_MEMBER_PROFILE_MARKETING_TENANT`、`COMMERCE_MARKETING_MEMBER_PROFILE_COMMERCE_TENANT` 和 `COMMERCE_MARKETING_MEMBER_PROFILE_BEARER_TOKEN`。
- 营销启用 `MARKETING_COMMERCE_PROFILE_ENABLED`，配置 `MARKETING_COMMERCE_PROFILE_TENANT`、`MARKETING_COMMERCE_PROFILE_BASE_URL` 和同一专用凭据 `MARKETING_COMMERCE_PROFILE_BEARER_TOKEN`。生产使用 HTTPS 和密钥系统。`MARKETING_COMMERCE_PROFILE_ALLOW_LOCAL_HTTP` 仅允许 DEV 模式通过 HTTP 访问。
- 在已配对营销租户用现有字段注册 API 注册 `member.level`，类型为 `STRING`，所属为 `commerce-platform`，来源为 `member_record.member_level`。用商城已有等级创建规则。关闭营销开关停止新计算，已保存快照按原有效期失效。
