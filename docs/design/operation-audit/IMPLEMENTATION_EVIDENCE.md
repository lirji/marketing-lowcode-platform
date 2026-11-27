# 营销操作审计验收

## OA-05 资金

- 资金库 Flyway V21 扩展尝试和结果。确认、释放、部分退款、剩余冲回和到期释放在账本事务保留结果。查询通过 `funding:reconcile` 和申请的租户、组织、门店权限校验。
- `GET /api/v1/promotion-applications/{applicationId}/operation-audits` 支持申请业务号及历史内部主键，返回分页结果。交易结算使用已验证的交易原生订单引用。已保存的重复操作关联原提交，历史应用缺少审计时允许关联为空。
- 真实 MySQL 的 `BenefitFundingIntegrationTest` 21 项通过。覆盖实际预算变动、状态和币种、重复回执、外租户拒绝、申请业务号查询。验证命令为 `./mvnw -B -pl services/benefit-funding-service -am -Dtest=BenefitFundingIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`。
