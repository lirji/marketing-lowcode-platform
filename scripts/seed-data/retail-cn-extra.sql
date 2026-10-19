-- 在闭环演示之上，为 DEV 默认货主 retail-cn 补充更多业务状态面。
-- 幂等：稳定 rcn-* 标识 + ON DUPLICATE KEY UPDATE，可重复执行。
-- 依赖 seed.sh 先写入两个演示租户的 system-mock.sql；只用于本地展示，不是生产发布/发奖证明。
-- 重跑会刷新约定的演示记录及相对时间；不要在承载真实业务的同名租户执行。

SET NAMES utf8mb4;
SET @tenant = 'retail-cn';
SET @organization = 'retail-business';
SET @actor = 'retail-cn-extra-seeder';
SET @campaign_active = 'mock-cmp-ha-2026';
SET @journey_definition = 'mock-journey-cart-recall';
SET @experiment = 'mock-exp-ha-7';
SET @now = DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t1h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t2h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t3h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 3 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t4h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 4 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t6h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 6 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t8h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 8 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t10h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @tomorrow = DATE_FORMAT(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @yesterday = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @next_month = DATE_FORMAT(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @subject_wait = 'rcn-subject-waiting-001';
SET @subject_fail = 'rcn-subject-failed-001';
SET @subject_sms = 'rcn-subject-sms-001';
SET @hash_wait = SHA2(@subject_wait, 256);
SET @hash_fail = SHA2(@subject_fail, 256);
SET @hash_sms = SHA2(@subject_sms, 256);

SET @draft_graph = JSON_OBJECT(
  'definitionId','rcn-offer-draft','dialect','OFFER_DECISION_DAG','dialectVersion','1.0.0',
  'nodes', JSON_ARRAY(
    JSON_OBJECT('id','start','stableTypeId','offer.start','semanticVersion','1.0.0','config',JSON_OBJECT()),
    JSON_OBJECT('id','end','stableTypeId','offer.end','semanticVersion','1.0.0','config',JSON_OBJECT())
  ),
  'edges', JSON_ARRAY(JSON_OBJECT('id','e1','sourceNodeId','start','sourcePort','next','targetNodeId','end','targetPort','in')),
  'variables', JSON_OBJECT(), 'annotations', JSON_OBJECT('terms','草稿：尚未配置优惠')
);
SET @review_graph = JSON_OBJECT(
  'definitionId','rcn-offer-review','dialect','OFFER_DECISION_DAG','dialectVersion','1.0.0',
  'nodes', JSON_ARRAY(
    JSON_OBJECT('id','start','stableTypeId','offer.start','semanticVersion','1.0.0','config',JSON_OBJECT()),
    JSON_OBJECT('id','discount','stableTypeId','offer.fixed','semanticVersion','1.0.0','config',JSON_OBJECT(
      'offerId','rcn-fixed-20','benefitDefinitionVersion','rcn-coupon-20','amountMinor','2000','thresholdMinor','9900',
      'validFrom','2025-01-01T00:00:00Z','validTo','2035-01-01T00:00:00Z','channels','APP','shopIds','all-shops',
      'fundingRules','PLATFORM|rcn-platform|5000;MERCHANT|rcn-shop|5000','reasonCode','RCN_FLASH_20')),
    JSON_OBJECT('id','end','stableTypeId','offer.end','semanticVersion','1.0.0','config',JSON_OBJECT())
  ),
  'edges', JSON_ARRAY(
    JSON_OBJECT('id','e1','sourceNodeId','start','sourcePort','next','targetNodeId','discount','targetPort','in'),
    JSON_OBJECT('id','e2','sourceNodeId','discount','sourcePort','next','targetNodeId','end','targetPort','in')
  ),
  'variables', JSON_OBJECT(), 'annotations', JSON_OBJECT('terms','限时满 99 减 20，待资金/合规会签')
);
SET @rejected_graph = JSON_OBJECT(
  'definitionId','rcn-offer-rejected','dialect','OFFER_DECISION_DAG','dialectVersion','1.0.0',
  'nodes', JSON_ARRAY(
    JSON_OBJECT('id','start','stableTypeId','offer.start','semanticVersion','1.0.0','config',JSON_OBJECT()),
    JSON_OBJECT('id','discount','stableTypeId','offer.percentage','semanticVersion','1.0.0','config',JSON_OBJECT(
      'offerId','rcn-percent-50','discountBasisPoints','5000','capMinor','999999','reasonCode','RCN_TOO_AGGRESSIVE')),
    JSON_OBJECT('id','end','stableTypeId','offer.end','semanticVersion','1.0.0','config',JSON_OBJECT())
  ),
  'edges', JSON_ARRAY(
    JSON_OBJECT('id','e1','sourceNodeId','start','sourcePort','next','targetNodeId','discount','targetPort','in'),
    JSON_OBJECT('id','e2','sourceNodeId','discount','sourcePort','next','targetNodeId','end','targetPort','in')
  ),
  'variables', JSON_OBJECT(), 'annotations', JSON_OBJECT('terms','五折，封顶 9999.99 元，优惠力度过高被合规驳回')
);
SET @sms_journey_graph = JSON_OBJECT(
  'definitionId','rcn-journey-sms','dialect','JOURNEY_STATE_MACHINE','dialectVersion','1.0.0',
  'nodes', JSON_ARRAY(
    JSON_OBJECT('id','trigger','stableTypeId','journey.trigger','semanticVersion','1.0.0','config',JSON_OBJECT('eventType','ORDER_PAID')),
    JSON_OBJECT('id','send','stableTypeId','journey.send','semanticVersion','1.0.0','config',JSON_OBJECT(
      'channel','SMS','templateId','rcn-tpl-pay-sms','templateVersion','1','campaignId','rcn-cmp-sms',
      'timezone','Asia/Shanghai','personalized','true','name','新客')),
    JSON_OBJECT('id','end','stableTypeId','journey.end','semanticVersion','1.0.0','config',JSON_OBJECT())
  ),
  'edges', JSON_ARRAY(
    JSON_OBJECT('id','e1','sourceNodeId','trigger','sourcePort','next','targetNodeId','send','targetPort','in'),
    JSON_OBJECT('id','e2','sourceNodeId','send','sourcePort','next','targetNodeId','end','targetPort','in')
  ),
  'variables', JSON_OBJECT('maxStepsPerSignal','10','maxIterations','1','stateTtlSeconds','86400'),
  'annotations', JSON_OBJECT('terms','支付成功后发送短信回执')
);

START TRANSACTION;

-- 活动生命周期：已批准 / 暂停 / 结束 / 短信触达活动。闭环脚本已覆盖 DRAFT / IN_REVIEW / ACTIVE。
INSERT INTO marketing_control.mk_campaign
  (tenant_id, campaign_id, name, objective, status, organization_id, shop_id, created_at, updated_at, campaign_type)
VALUES
  (@tenant,'rcn-cmp-approved','秋季会员日方案','已批准待发布', 'APPROVED', @organization, NULL, @t8h, @t4h, 'STANDARD'),
  (@tenant,'rcn-cmp-paused','暑期家电直降','高峰期临时暂停', 'PAUSED', @organization, NULL, @t10h, @t2h, 'STANDARD'),
  (@tenant,'rcn-cmp-ended','618 主会场','账期已关闭', 'ENDED', @organization, NULL, @t10h, @t6h, 'STANDARD'),
  (@tenant,'rcn-cmp-sms','支付成功关怀短信','支付后 SMS 触达', 'ACTIVE', @organization, NULL, @t6h, @now, 'STANDARD')
ON DUPLICATE KEY UPDATE
  name=VALUES(name), objective=VALUES(objective), status=VALUES(status),
  organization_id=VALUES(organization_id), shop_id=VALUES(shop_id),
  campaign_type=VALUES(campaign_type), updated_at=VALUES(updated_at);

-- 定义状态：草稿 / 审核中 / 已驳回 / 已批准短信旅程。
INSERT INTO marketing_control.mk_definition_version
  (tenant_id, definition_id, campaign_id, version_no, dialect, graph_json, semantic_hash, status, created_by, created_at, updated_at)
VALUES
  (@tenant,'rcn-offer-draft','mock-cmp-new-user',1,'OFFER_DECISION_DAG',@draft_graph,CONCAT('sha256:',REPEAT('d',64)),'DRAFT',@actor,@t4h,@t3h),
  (@tenant,'rcn-offer-review','rcn-cmp-approved',1,'OFFER_DECISION_DAG',@review_graph,CONCAT('sha256:',REPEAT('e',64)),'IN_REVIEW',@actor,@t4h,@t3h),
  (@tenant,'rcn-offer-rejected','rcn-cmp-ended',1,'OFFER_DECISION_DAG',@rejected_graph,CONCAT('sha256:',REPEAT('f',64)),'REJECTED',@actor,@t8h,@t6h),
  (@tenant,'rcn-journey-sms','rcn-cmp-sms',1,'JOURNEY_STATE_MACHINE',@sms_journey_graph,CONCAT('sha256:',REPEAT('0',64)),'APPROVED',@actor,@t6h,@t4h)
ON DUPLICATE KEY UPDATE
  campaign_id=VALUES(campaign_id), dialect=VALUES(dialect), graph_json=VALUES(graph_json),
  semantic_hash=VALUES(semantic_hash), status=VALUES(status), created_by=VALUES(created_by), updated_at=VALUES(updated_at);

INSERT INTO marketing_control.mk_approval_case
  (tenant_id, case_id, definition_id, definition_version, submitted_by, required_roles, status, created_at, updated_at)
VALUES
  (@tenant,'rcn-apr-offer-open','rcn-offer-review',1,@actor,'BUSINESS,FINANCE,COMPLIANCE','OPEN',@t4h,@t3h),
  (@tenant,'rcn-apr-offer-rejected','rcn-offer-rejected',1,@actor,'BUSINESS,COMPLIANCE','REJECTED',@t8h,@t6h),
  (@tenant,'rcn-apr-sms','rcn-journey-sms',1,@actor,'BUSINESS,COMPLIANCE','APPROVED',@t6h,@t4h)
ON DUPLICATE KEY UPDATE
  definition_id=VALUES(definition_id), definition_version=VALUES(definition_version), submitted_by=VALUES(submitted_by),
  required_roles=VALUES(required_roles), status=VALUES(status), updated_at=VALUES(updated_at);

INSERT INTO marketing_control.mk_approval_decision
  (tenant_id, case_id, role_name, actor_id, decided_at)
VALUES
  (@tenant,'rcn-apr-offer-open','BUSINESS','rcn-approver-business',@t3h),
  (@tenant,'rcn-apr-offer-rejected','BUSINESS','rcn-rejector-business',@t6h),
  (@tenant,'rcn-apr-sms','BUSINESS','rcn-sms-business',@t4h),
  (@tenant,'rcn-apr-sms','COMPLIANCE','rcn-sms-compliance',@t4h)
ON DUPLICATE KEY UPDATE actor_id=VALUES(actor_id), decided_at=VALUES(decided_at);

-- 发布面：预热中的西区、已回滚的南区。清单必须带对象形态的制品闭包，不能把 JSON 当字符串塞进数组。
SET @offer_checksum = CONCAT('sha256:', REPEAT('3', 64));
SET @offer_hash = CONCAT('sha256:', REPEAT('1', 64));
SET @staged_manifest = JSON_OBJECT(
  'manifestId','rcn-manifest-decision-west','tenantId',JSON_OBJECT('value',@tenant),
  'environment','local','cell','cell-b','runtime','decision','namespace','main','generation',1843,'stableGeneration',1842,
  'canaryGenerations',JSON_ARRAY(1843),'retainedGenerations',JSON_ARRAY(1842),
  'artifacts',JSON_ARRAY(JSON_OBJECT(
    'artifactId','mock-artifact-offer-v1','type','OFFER_POLICY','uri','compiler://mock-artifact-offer-v1',
    'checksum',@offer_checksum,'sourceDigest',@offer_hash,'signatureKeyId','mock-release-key','signature','mock-signature-offer',
    'abi','offer-policy-v1','definitionId','mock-offer-ha-2026','definitionVersion',1)),
  'schemaVersions',JSON_OBJECT('terms','terms-v1'),'canaryBasisPoints',1000,
  'activationAt',@tomorrow,'expiresAt',@next_month,'createdBy',@actor,'approvalCaseIds',JSON_ARRAY('rcn-apr-offer-open'),
  'createdAt',@t2h,'signature','rcn-manifest-signature-west');
SET @rolled_manifest = JSON_OBJECT(
  'manifestId','rcn-manifest-decision-south','tenantId',JSON_OBJECT('value',@tenant),
  'environment','local','cell','cell-c','runtime','decision','namespace','main','generation',1840,'stableGeneration',1839,
  'canaryGenerations',JSON_ARRAY(),'retainedGenerations',JSON_ARRAY(1839),
  'artifacts',JSON_ARRAY(JSON_OBJECT(
    'artifactId','mock-artifact-offer-v1','type','OFFER_POLICY','uri','compiler://mock-artifact-offer-v1',
    'checksum',@offer_checksum,'sourceDigest',@offer_hash,'signatureKeyId','mock-release-key','signature','mock-signature-offer',
    'abi','offer-policy-v1','definitionId','mock-offer-ha-2026','definitionVersion',1)),
  'schemaVersions',JSON_OBJECT('terms','terms-v1'),'canaryBasisPoints',0,
  'activationAt',@t10h,'expiresAt',@t6h,'createdBy',@actor,'approvalCaseIds',JSON_ARRAY('rcn-apr-offer-rejected'),
  'createdAt',@t10h,'signature','rcn-manifest-signature-south');

INSERT INTO marketing_control.mk_release_slot
  (tenant_id, environment_name, cell_id, runtime_name, namespace_name, stable_generation, desired_generation, latest_generation, activation_sequence, updated_at)
VALUES
  (@tenant,'local','cell-b','decision','main',1842,1843,1843,1,@now),
  (@tenant,'local','cell-c','decision','main',1839,1839,1840,3,@now)
ON DUPLICATE KEY UPDATE stable_generation=VALUES(stable_generation), desired_generation=VALUES(desired_generation),
  latest_generation=VALUES(latest_generation), activation_sequence=VALUES(activation_sequence), updated_at=VALUES(updated_at);

INSERT INTO marketing_control.mk_release_manifest
  (tenant_id, manifest_id, environment_name, cell_id, runtime_name, namespace_name, generation_no, state_name, manifest_json, created_at)
VALUES
  (@tenant,'rcn-manifest-decision-west','local','cell-b','decision','main',1843,'STAGED',@staged_manifest,@t2h),
  (@tenant,'rcn-manifest-decision-south','local','cell-c','decision','main',1840,'ROLLED_BACK',@rolled_manifest,@t10h)
ON DUPLICATE KEY UPDATE state_name=VALUES(state_name), manifest_json=VALUES(manifest_json), created_at=VALUES(created_at);

INSERT INTO marketing_control.mk_runtime_ack
  (tenant_id, manifest_id, runtime_id, status_name, build_digest, supported_abis, warmed_artifact_ids, capacity_value,
   acknowledged_at, signature_key_id, signature_value)
VALUES
  (@tenant,'rcn-manifest-decision-west','rcn-decision-runtime-b','WARMING','rcn-build-west','offer-policy-v1','mock-artifact-offer-v1',120,@now,'mock-runtime-key','rcn-ack-west')
ON DUPLICATE KEY UPDATE status_name=VALUES(status_name), build_digest=VALUES(build_digest), supported_abis=VALUES(supported_abis),
  warmed_artifact_ids=VALUES(warmed_artifact_ids), capacity_value=VALUES(capacity_value), acknowledged_at=VALUES(acknowledged_at),
  signature_key_id=VALUES(signature_key_id), signature_value=VALUES(signature_value);

INSERT INTO marketing_control.mk_kill_switch
  (tenant_id, namespace_name, switch_sequence, enabled_value, reason_text, updated_by, updated_at, directive_json)
VALUES
  (@tenant,'main',1,false,'演示：默认关闭，不切断流量',@actor,@now,JSON_OBJECT('enabled',false,'reason','demo-off'))
ON DUPLICATE KEY UPDATE switch_sequence=VALUES(switch_sequence), enabled_value=VALUES(enabled_value),
  reason_text=VALUES(reason_text), updated_by=VALUES(updated_by), updated_at=VALUES(updated_at), directive_json=VALUES(directive_json);

-- 人群：新客分群 + 过期快照。
INSERT INTO marketing_audience.mk_field_definition
  (tenant_id, field_id, value_type, owner_name, provenance, classification, allowed_uses, max_age_seconds,
   null_policy, missing_policy, retention_days, created_at)
VALUES
  (@tenant,'member.registeredDays','DECIMAL','crm-profile','crm.member_profile.registered_days','INTERNAL','ELIGIBILITY',86400,'NO_MATCH','NO_MATCH',365,@t8h),
  (@tenant,'order.paidCount','DECIMAL','order-profile','order.summary.paid_count','INTERNAL','ELIGIBILITY,PERSONALIZATION',3600,'NO_MATCH','NO_MATCH',180,@t8h)
ON DUPLICATE KEY UPDATE value_type=VALUES(value_type), owner_name=VALUES(owner_name), provenance=VALUES(provenance),
  classification=VALUES(classification), allowed_uses=VALUES(allowed_uses), max_age_seconds=VALUES(max_age_seconds),
  null_policy=VALUES(null_policy), missing_policy=VALUES(missing_policy), retention_days=VALUES(retention_days);

SET @new_user_rule = JSON_OBJECT('match','ALL','conditions',JSON_ARRAY(
  JSON_OBJECT('fieldId','member.registeredDays','operator','LTE','value','7'),
  JSON_OBJECT('fieldId','order.paidCount','operator','EQ','value','0')));
INSERT INTO marketing_audience.mk_segment_definition
  (tenant_id, segment_id, version_no, name, rule_json, rule_hash, state_name, created_by, created_at)
VALUES
  (@tenant,'rcn-aud-new-user',1,'七日未首购新客',@new_user_rule,CONCAT('sha256:',REPEAT('a',64)),'ACTIVE',@actor,@t6h)
ON DUPLICATE KEY UPDATE name=VALUES(name), rule_json=VALUES(rule_json), rule_hash=VALUES(rule_hash),
  state_name=VALUES(state_name), created_by=VALUES(created_by), created_at=VALUES(created_at);

INSERT INTO marketing_audience.mk_audience_snapshot
  (tenant_id, snapshot_id, segment_id, segment_version, as_of_time, watermark_time, expires_at, member_count, checksum, state_name, created_at)
VALUES
  (@tenant,'rcn-snap-new-user-v1','rcn-aud-new-user',1,@t4h,@t4h,@next_month,8600,CONCAT('sha256:',REPEAT('b',64)),'READY',@t4h),
  (@tenant,'rcn-snap-new-user-expired','rcn-aud-new-user',1,@t10h,@t10h,@yesterday,1200,CONCAT('sha256:',REPEAT('c',64)),'EXPIRED',@t10h)
ON DUPLICATE KEY UPDATE segment_id=VALUES(segment_id), segment_version=VALUES(segment_version), as_of_time=VALUES(as_of_time),
  watermark_time=VALUES(watermark_time), expires_at=VALUES(expires_at), member_count=VALUES(member_count), checksum=VALUES(checksum),
  state_name=VALUES(state_name), created_at=VALUES(created_at);

INSERT INTO marketing_audience.mk_audience_member
  (tenant_id, snapshot_id, subject_hash, membership_version, active_value, updated_at)
VALUES
  (@tenant,'rcn-snap-new-user-v1',@hash_sms,1,true,@now),
  (@tenant,'rcn-snap-new-user-expired',@hash_fail,1,false,@t6h)
ON DUPLICATE KEY UPDATE membership_version=VALUES(membership_version), active_value=VALUES(active_value), updated_at=VALUES(updated_at);

-- 权益目录与资金账户：草稿 / 在线 / 退役。不再写入残缺优惠申请，避免资金对账被演示数据打穿。
DELETE FROM marketing_benefit.mk_reservation_escrow_allocation
WHERE tenant_id=@tenant AND application_id IN ('rcn-app-reserved','rcn-app-confirmed','rcn-app-cancelled','rcn-app-expired');
DELETE FROM marketing_benefit.mk_reservation_item
WHERE tenant_id=@tenant AND application_id IN ('rcn-app-reserved','rcn-app-confirmed','rcn-app-cancelled','rcn-app-expired');
DELETE FROM marketing_benefit.mk_funding_ledger
WHERE tenant_id=@tenant AND application_id IN ('rcn-app-reserved','rcn-app-confirmed','rcn-app-cancelled','rcn-app-expired');
DELETE FROM marketing_benefit.mk_promotion_application
WHERE tenant_id=@tenant AND application_id IN ('rcn-app-reserved','rcn-app-confirmed','rcn-app-cancelled','rcn-app-expired');

SET @inv_flash = 'INVENTORY:rcn-coupon-20';
SET @budget_plat = 'BUDGET:PLATFORM:rcn-platform:CNY';
SET @budget_shop = 'BUDGET:MERCHANT:rcn-shop:CNY';
INSERT INTO marketing_benefit.mk_resource_account
  (tenant_id, resource_key, resource_type, currency_code, authorized_amount, available_amount, reserved_amount,
   consumed_amount, returned_amount, fencing_epoch, version_no, state_name, updated_at)
VALUES
  (@tenant,@inv_flash,'INVENTORY','UNIT',100000,100000,0,0,0,7,1,'ACTIVE',@now),
  (@tenant,@budget_plat,'BUDGET','CNY',50000000,50000000,0,0,0,7,1,'ACTIVE',@now),
  (@tenant,@budget_shop,'BUDGET','CNY',30000000,30000000,0,0,0,7,1,'ACTIVE',@now),
  (@tenant,'INVENTORY:rcn-retired-10','INVENTORY','UNIT',1000,1000,0,0,0,3,1,'CLOSED',@t6h)
ON DUPLICATE KEY UPDATE resource_type=VALUES(resource_type), currency_code=VALUES(currency_code),
  authorized_amount=VALUES(authorized_amount), available_amount=VALUES(available_amount), reserved_amount=VALUES(reserved_amount),
  consumed_amount=VALUES(consumed_amount), returned_amount=VALUES(returned_amount), fencing_epoch=VALUES(fencing_epoch),
  version_no=VALUES(version_no), state_name=VALUES(state_name), updated_at=VALUES(updated_at);

INSERT INTO marketing_benefit.mk_benefit_definition
  (tenant_id, benefit_id, version_no, name_text, status_name, resource_key, benefit_sku_id, policy_json, created_by, created_at)
VALUES
  (@tenant,'rcn-coupon-20',1,'满 99 减 20 闪购券','ACTIVE',@inv_flash,'SKU-COUPON-20',
   JSON_OBJECT('type','COUPON','thresholdMinor',9900,'discountMinor',2000,'platformShare',50,'merchantShare',50,'validity','P3D'),@actor,@t6h),
  (@tenant,'rcn-benefit-draft',1,'未发布新人礼金','DRAFT','','SKU-CASH-10',
   JSON_OBJECT('type','CASH','amountMinor',1000,'validity','P7D'),@actor,@t3h),
  (@tenant,'rcn-benefit-retired',1,'已退役满减 10 元','RETIRED','INVENTORY:rcn-retired-10',NULL,
   JSON_OBJECT('type','COUPON','thresholdMinor',5000,'discountMinor',1000),@actor,@t10h)
ON DUPLICATE KEY UPDATE name_text=VALUES(name_text), status_name=VALUES(status_name), resource_key=VALUES(resource_key),
  benefit_sku_id=VALUES(benefit_sku_id), policy_json=VALUES(policy_json), created_by=VALUES(created_by), created_at=VALUES(created_at);
INSERT INTO marketing_benefit.mk_benefit_definition_head (tenant_id,benefit_id,latest_version)
VALUES (@tenant,'rcn-coupon-20',1), (@tenant,'rcn-benefit-draft',1), (@tenant,'rcn-benefit-retired',1)
ON DUPLICATE KEY UPDATE latest_version=VALUES(latest_version);

INSERT INTO marketing_benefit.mk_award_intent_dedupe
  (tenant_id, source_system, source_request_id, request_hash, result_type, lease_owner, lease_until, lease_version, created_at, updated_at)
VALUES
  (@tenant,'marketing','rcn-req-sent',REPEAT('a',64),'OUTBOX',NULL,NULL,1,@t3h,@t2h),
  (@tenant,'marketing','rcn-req-pending',REPEAT('b',64),'OUTBOX',NULL,NULL,1,@t1h,@t1h),
  (@tenant,'marketing','rcn-req-dead',REPEAT('c',64),'OUTBOX',NULL,NULL,2,@t6h,@t4h),
  (@tenant,'marketing','rcn-req-block',REPEAT('d',64),'BLOCK',NULL,NULL,1,@t2h,@t2h)
ON DUPLICATE KEY UPDATE request_hash=VALUES(request_hash), result_type=VALUES(result_type),
  lease_owner=VALUES(lease_owner), lease_until=VALUES(lease_until), lease_version=VALUES(lease_version), updated_at=VALUES(updated_at);

INSERT INTO marketing_benefit.mk_award_intent_outbox
  (tenant_id, intent_id, source_system, source_request_id, campaign_id, definition_version, subject_hash,
   delivery_mode, request_hash, payload_hash, payload_json, status_name, delivery_result, attempt_count,
   next_attempt_at, last_error, created_at, updated_at, sent_at, benefit_order_no)
VALUES
  (@tenant,'rcn-intent-sent','marketing','rcn-req-sent',@campaign_active,1,@hash_sms,'CENTER',REPEAT('a',64),REPEAT('1',64),
   JSON_OBJECT('skuId','SKU-COUPON-20','amountMinor',2000),'SENT','CENTER_ACCEPTED',1,@t2h,'',@t3h,@t2h,@t2h,'ORD-MOCK-CASH-001'),
  (@tenant,'rcn-intent-pending','marketing','rcn-req-pending',@campaign_active,1,@hash_wait,'CENTER',REPEAT('b',64),REPEAT('2',64),
   JSON_OBJECT('skuId','SKU-COUPON-20','amountMinor',2000),'PENDING','CENTER_ENQUEUED',0,@now,'',@t1h,@t1h,NULL,NULL),
  (@tenant,'rcn-intent-dead','marketing','rcn-req-dead',@campaign_active,1,@hash_fail,'CENTER',REPEAT('c',64),REPEAT('3',64),
   JSON_OBJECT('skuId','SKU-CASH-10','amountMinor',1000),'DEAD','CENTER_ENQUEUED',8,@t4h,'权益中台超时后转入死信',@t6h,@t4h,NULL,NULL)
ON DUPLICATE KEY UPDATE campaign_id=VALUES(campaign_id), status_name=VALUES(status_name), delivery_result=VALUES(delivery_result),
  attempt_count=VALUES(attempt_count), last_error=VALUES(last_error), updated_at=VALUES(updated_at), sent_at=VALUES(sent_at),
  benefit_order_no=VALUES(benefit_order_no);

INSERT INTO marketing_benefit.mk_award_intent_block
  (tenant_id, intent_id, source_system, source_request_id, campaign_id, definition_version, subject_hash,
   delivery_mode, request_hash, risk_action, risk_reason, risk_decision_id, created_at, updated_at)
VALUES
  (@tenant,'rcn-intent-block','marketing','rcn-req-block',@campaign_active,1,@hash_fail,'CENTER',REPEAT('d',64),
   'REJECT','演示：风控拒绝高风险主体','risk-dec-rcn-001',@t2h,@t2h)
ON DUPLICATE KEY UPDATE campaign_id=VALUES(campaign_id), risk_action=VALUES(risk_action), risk_reason=VALUES(risk_reason),
  risk_decision_id=VALUES(risk_decision_id), updated_at=VALUES(updated_at);

-- 旅程实例：等待中 / 运行中 / 失败；短信旅程已完成。
SET @sms_plan = JSON_OBJECT(
  'journeyId','rcn-journey-sms','version',1,'startNodeId','trigger',
  'nodes',JSON_OBJECT(
    'trigger',JSON_OBJECT('id','trigger','type','TRIGGER','config',JSON_OBJECT('eventType','ORDER_PAID'),'routes',JSON_OBJECT('next','send')),
    'send',JSON_OBJECT('id','send','type','SEND','config',JSON_OBJECT('channel','SMS','templateId','rcn-tpl-pay-sms','templateVersion',1,'campaignId','rcn-cmp-sms'),'routes',JSON_OBJECT('next','end')),
    'end',JSON_OBJECT('id','end','type','END','config',JSON_OBJECT(),'routes',JSON_OBJECT())),
  'maxStepsPerSignal',10,'maxIterations',1,'stateTtl','PT24H');
INSERT INTO marketing_journey.mk_journey_definition
  (tenant_id,journey_id,version_no,plan_json,state_name,created_by,created_at)
VALUES (@tenant,'rcn-journey-sms',1,@sms_plan,'ACTIVE',@actor,@t4h)
ON DUPLICATE KEY UPDATE plan_json=VALUES(plan_json),state_name=VALUES(state_name),created_by=VALUES(created_by),created_at=VALUES(created_at);

INSERT INTO marketing_journey.mk_enrollment
  (tenant_id,enrollment_id,journey_id,journey_version,subject_token,trigger_event_id,status_name,current_node_id,
   snapshot_json,projection_topic,projection_partition,projection_offset,created_at,updated_at)
VALUES
  (@tenant,'rcn-enr-waiting',@journey_definition,1,@subject_wait,'rcn-signal-wait-001','WAITING','wait',
   JSON_OBJECT('tenantId',@tenant,'enrollmentId','rcn-enr-waiting','subjectToken',@subject_wait,'journeyId',@journey_definition,
     'journeyVersion',1,'currentNodeId','wait','status','WAITING','variables',JSON_OBJECT('campaignId',@campaign_active),
     'processedSignalIds',JSON_ARRAY('rcn-signal-wait-001'),'iterations',JSON_OBJECT(),'nodeExecutions',JSON_OBJECT('trigger',1,'wait',1),'updatedAt',@t1h),
   'mk.journey.output.v1',0,7,@t2h,@t1h),
  (@tenant,'rcn-enr-running',@journey_definition,1,@subject_sms,'rcn-signal-run-001','RUNNING','send',
   JSON_OBJECT('tenantId',@tenant,'enrollmentId','rcn-enr-running','subjectToken',@subject_sms,'journeyId',@journey_definition,
     'journeyVersion',1,'currentNodeId','send','status','RUNNING','variables',JSON_OBJECT('campaignId',@campaign_active),
     'processedSignalIds',JSON_ARRAY('rcn-signal-run-001'),'iterations',JSON_OBJECT(),'nodeExecutions',JSON_OBJECT('trigger',1,'wait',1,'send',1),'updatedAt',@now),
   'mk.journey.output.v1',0,8,@t3h,@now),
  (@tenant,'rcn-enr-failed',@journey_definition,1,@subject_fail,'rcn-signal-fail-001','FAILED','send',
   JSON_OBJECT('tenantId',@tenant,'enrollmentId','rcn-enr-failed','subjectToken',@subject_fail,'journeyId',@journey_definition,
     'journeyVersion',1,'currentNodeId','send','status','FAILED','variables',JSON_OBJECT('campaignId',@campaign_active,'error','PROVIDER_UNAVAILABLE'),
     'processedSignalIds',JSON_ARRAY('rcn-signal-fail-001'),'iterations',JSON_OBJECT(),'nodeExecutions',JSON_OBJECT('trigger',1,'send',1),'updatedAt',@t2h),
   'mk.journey.output.v1',0,9,@t4h,@t2h),
  (@tenant,'rcn-enr-sms','rcn-journey-sms',1,@subject_sms,'rcn-signal-sms-001','COMPLETED','end',
   JSON_OBJECT('tenantId',@tenant,'enrollmentId','rcn-enr-sms','subjectToken',@subject_sms,'journeyId','rcn-journey-sms',
     'journeyVersion',1,'currentNodeId','end','status','COMPLETED','variables',JSON_OBJECT('campaignId','rcn-cmp-sms'),
     'processedSignalIds',JSON_ARRAY('rcn-signal-sms-001'),'iterations',JSON_OBJECT(),'nodeExecutions',JSON_OBJECT('trigger',1,'send',1,'end',1),'updatedAt',@t1h),
   'mk.journey.output.v1',0,10,@t3h,@t1h)
ON DUPLICATE KEY UPDATE status_name=VALUES(status_name), current_node_id=VALUES(current_node_id),
  snapshot_json=VALUES(snapshot_json), updated_at=VALUES(updated_at);

INSERT INTO marketing_journey.mk_journey_timer
  (tenant_id, timer_key, enrollment_id, fire_at, state_name, created_at)
VALUES (@tenant,'rcn-timer-wait','rcn-enr-waiting',@tomorrow,'SCHEDULED',@t1h)
ON DUPLICATE KEY UPDATE fire_at=VALUES(fire_at), state_name=VALUES(state_name), created_at=VALUES(created_at);

INSERT INTO marketing_journey.mk_node_effect_intent
  (tenant_id,command_id,enrollment_id,node_id,effect_type,payload_json,state_name,created_at)
VALUES
  (@tenant,'rcn-cmd-sms-001','rcn-enr-sms','send','SEND',JSON_OBJECT('campaignId','rcn-cmp-sms','channel','SMS','templateId','rcn-tpl-pay-sms'),'DISPATCHED',@t1h),
  (@tenant,'rcn-cmd-fail-001','rcn-enr-failed','send','SEND',JSON_OBJECT('campaignId',@campaign_active,'channel','PUSH','templateId','mock-tpl-cart-push'),'DISPATCH_FAILED',@t2h)
ON DUPLICATE KEY UPDATE payload_json=VALUES(payload_json), state_name=VALUES(state_name), created_at=VALUES(created_at);

-- 触达：短信成功、Push 失败、待重试、已点击；另有抑制名单。
INSERT INTO marketing_engagement.mk_consent
  (tenant_id,subject_token,channel_name,allowed_value,minor_value,personalization_allowed,version_no,source_name,effective_at,updated_at)
VALUES
  (@tenant,@subject_sms,'SMS',true,false,true,1,'retail-cn-extra',@t6h,@now),
  (@tenant,@subject_fail,'PUSH',false,false,false,2,'retail-cn-extra',@t4h,@now)
ON DUPLICATE KEY UPDATE allowed_value=VALUES(allowed_value), minor_value=VALUES(minor_value),
  personalization_allowed=VALUES(personalization_allowed), version_no=VALUES(version_no), updated_at=VALUES(updated_at);

INSERT INTO marketing_engagement.mk_suppression
  (tenant_id,subject_token,channel_name,reason_text,expires_at,updated_at)
VALUES (@tenant,@subject_fail,'PUSH','用户投诉后人工抑制',@next_month,@now)
ON DUPLICATE KEY UPDATE reason_text=VALUES(reason_text), expires_at=VALUES(expires_at), updated_at=VALUES(updated_at);

INSERT INTO marketing_engagement.mk_frequency_policy
  (tenant_id,campaign_id,channel_name,window_seconds,max_contacts,quiet_start,quiet_end,updated_at)
VALUES (@tenant,'rcn-cmp-sms','SMS',86400,1,'21:00:00','08:00:00',@now)
ON DUPLICATE KEY UPDATE window_seconds=VALUES(window_seconds), max_contacts=VALUES(max_contacts),
  quiet_start=VALUES(quiet_start), quiet_end=VALUES(quiet_end), updated_at=VALUES(updated_at);

INSERT INTO marketing_engagement.mk_template_version
  (tenant_id,template_id,version_no,channel_name,content_text,required_variables,state_name,created_by,created_at)
VALUES
  (@tenant,'rcn-tpl-pay-sms',1,'SMS','{{name}}，您的订单已支付成功，满减券已入账。','name','ACTIVE',@actor,@t4h),
  (@tenant,'rcn-tpl-email',1,'EMAIL','亲爱的 {{name}}，PLUS 会员日即将开始。','name','ACTIVE',@actor,@t4h),
  (@tenant,'rcn-tpl-retired',1,'PUSH','旧文案','name','RETIRED',@actor,@t10h)
ON DUPLICATE KEY UPDATE channel_name=VALUES(channel_name), content_text=VALUES(content_text),
  required_variables=VALUES(required_variables), state_name=VALUES(state_name), created_by=VALUES(created_by), created_at=VALUES(created_at);

INSERT INTO marketing_engagement.mk_contact_attempt
  (tenant_id,contact_id,contact_key,subject_token,campaign_id,channel_name,template_id,template_version,state_name,
   variables_json,provider_request_id,provider_code,requested_at,updated_at)
VALUES
  (@tenant,'rcn-contact-sms','rcn-cmd-sms-001',@subject_sms,'rcn-cmp-sms','SMS','rcn-tpl-pay-sms',1,'DELIVERED',
   JSON_OBJECT('name','新客'),'rcn-provider-sms-001','MOCK_DELIVERED',@t1h,@now),
  (@tenant,'rcn-contact-fail','rcn-cmd-fail-001',@subject_fail,@campaign_active,'PUSH','mock-tpl-cart-push',1,'FAILED',
   JSON_OBJECT('name','已退订用户'),'rcn-provider-fail-001','PROVIDER_UNAVAILABLE',@t2h,@t2h),
  (@tenant,'rcn-contact-retry','rcn-cmd-retry-001',@subject_wait,@campaign_active,'PUSH','mock-tpl-cart-push',1,'RETRY_PENDING',
   JSON_OBJECT('name','PLUS 会员'),NULL,'THROTTLED',@t1h,@now),
  (@tenant,'rcn-contact-click','rcn-cmd-click-001',@subject_sms,@campaign_active,'PUSH','mock-tpl-cart-push',1,'CLICKED',
   JSON_OBJECT('name','新客'),'rcn-provider-click-001','MOCK_CLICKED',@t3h,@t1h)
ON DUPLICATE KEY UPDATE state_name=VALUES(state_name), variables_json=VALUES(variables_json),
  provider_request_id=VALUES(provider_request_id), provider_code=VALUES(provider_code),
  requested_at=VALUES(requested_at), updated_at=VALUES(updated_at);

INSERT INTO marketing_engagement.mk_engagement_command
  (tenant_id,command_id,effect_type,enrollment_id,payload_hash,payload_json,state_name,contact_id,provider_code,last_error,created_at,updated_at)
VALUES
  (@tenant,'rcn-cmd-sms-001','SEND','rcn-enr-sms',REPEAT('1',64),JSON_OBJECT('channel','SMS'),'SUCCEEDED','rcn-contact-sms','MOCK_DELIVERED','',@t1h,@now),
  (@tenant,'rcn-cmd-fail-001','SEND','rcn-enr-failed',REPEAT('2',64),JSON_OBJECT('channel','PUSH'),'FAILED','rcn-contact-fail','PROVIDER_UNAVAILABLE','供应商不可用',@t2h,@t2h)
ON DUPLICATE KEY UPDATE payload_hash=VALUES(payload_hash), payload_json=VALUES(payload_json), state_name=VALUES(state_name),
  contact_id=VALUES(contact_id), provider_code=VALUES(provider_code), last_error=VALUES(last_error), updated_at=VALUES(updated_at);

-- 事件：支付信号 + 又一条隔离。
INSERT INTO marketing_events.mk_event_receipt
  (tenant_id,receipt_id,source_id,event_id,event_type,business_key,aggregate_version,status_name,reason_code,
   payload_hash,event_json,occurred_at,ingested_at)
VALUES
  (@tenant,'rcn-receipt-paid','mock-source-commerce','rcn-signal-sms-001','JOURNEY_SIGNAL','rcn-enr-sms',1,'ACCEPTED','',REPEAT('1',64),
   JSON_OBJECT('eventId','rcn-signal-sms-001','sourceId','mock-source-commerce','eventType','JOURNEY_SIGNAL','businessKey','rcn-enr-sms',
     'subjectToken',@subject_sms,'occurredAt',@t3h,'schemaVersion','1.0.0','data',JSON_OBJECT('journeyId','rcn-journey-sms')),@t3h,@t3h),
  (@tenant,'rcn-receipt-late','mock-source-commerce','rcn-late-event-001','MARKETING_FACT','rcn-order-expired',1,'QUARANTINED','EVENT_TOO_LATE',REPEAT('2',64),
   JSON_OBJECT('eventId','rcn-late-event-001','eventType','MARKETING_FACT','note','超过允许延迟'),@t10h,@t8h)
ON DUPLICATE KEY UPDATE event_type=VALUES(event_type), business_key=VALUES(business_key), status_name=VALUES(status_name),
  reason_code=VALUES(reason_code), payload_hash=VALUES(payload_hash), event_json=VALUES(event_json),
  occurred_at=VALUES(occurred_at), ingested_at=VALUES(ingested_at);

INSERT INTO marketing_events.mk_quarantine
  (tenant_id,quarantine_id,receipt_id,reason_code,event_json,state_name,created_at,replayed_at)
VALUES (@tenant,'rcn-quarantine-late','rcn-receipt-late','EVENT_TOO_LATE',
  JSON_OBJECT('eventId','rcn-late-event-001','eventType','MARKETING_FACT','note','超过允许延迟'),'PENDING',@t8h,NULL)
ON DUPLICATE KEY UPDATE reason_code=VALUES(reason_code), event_json=VALUES(event_json), state_name=VALUES(state_name), created_at=VALUES(created_at);

-- 衡量：补最近六小时转化（跨北京时间零点时部分记录属于昨日）；对照实验组。
INSERT INTO marketing_measurement.mk_experiment_assignment
  (tenant_id,experiment_id,version_no,layer_name,randomization_unit,variant_id,holdout_value,bucket_no,assigned_at)
VALUES
  (@tenant,@experiment,'1','checkout-offer',@subject_sms,'CONTROL',true,1201,@t6h),
  (@tenant,@experiment,'1','checkout-offer',@subject_wait,'VARIANT_A',false,4410,@t4h)
ON DUPLICATE KEY UPDATE variant_id=VALUES(variant_id), holdout_value=VALUES(holdout_value), bucket_no=VALUES(bucket_no), assigned_at=VALUES(assigned_at);

INSERT INTO marketing_measurement.mk_fact
  (tenant_id,event_id,fact_type,business_key,subject_hash,occurred_at,ingested_at,schema_version,payload_hash,
   attributes_json,correction_of,correction_root_id,corrected_value,revenue_minor,cost_minor,experiment_id,experiment_version,variant_id)
VALUES
  (@tenant,'rcn-fact-conv-1h','CONVERSION','rcn-order-confirmed',@hash_sms,@t1h,@t1h,'1.0.0',REPEAT('a',64),JSON_OBJECT('campaignId',@campaign_active),'','rcn-fact-conv-1h',false,128800,2000,@experiment,'1','VARIANT_A'),
  (@tenant,'rcn-fact-conv-3h','CONVERSION','rcn-order-sms',@hash_sms,@t3h,@t3h,'1.0.0',REPEAT('b',64),JSON_OBJECT('campaignId','rcn-cmp-sms'),'','rcn-fact-conv-3h',false,89900,0,@experiment,'1','CONTROL'),
  (@tenant,'rcn-fact-conv-6h','CONVERSION','rcn-order-holdout',@hash_wait,@t6h,@t6h,'1.0.0',REPEAT('c',64),JSON_OBJECT('campaignId',@campaign_active),'','rcn-fact-conv-6h',false,45900,0,@experiment,'1','CONTROL'),
  (@tenant,'rcn-fact-click-2h','CLICK','rcn-contact-click',@hash_sms,@t2h,@t2h,'1.0.0',REPEAT('d',64),JSON_OBJECT('campaignId',@campaign_active),'','rcn-fact-click-2h',false,0,0,@experiment,'1','VARIANT_A')
ON DUPLICATE KEY UPDATE fact_type=VALUES(fact_type), business_key=VALUES(business_key), occurred_at=VALUES(occurred_at),
  ingested_at=VALUES(ingested_at), revenue_minor=VALUES(revenue_minor), cost_minor=VALUES(cost_minor), variant_id=VALUES(variant_id);

INSERT INTO marketing_measurement.mk_dashboard_projection_delta
  (tenant_id,delta_id,root_event_id,source_event_id,revision_no,operation_name,fact_type,business_key,campaign_id,
   experiment_id,variant_id,subject_hash,count_delta,revenue_delta_minor,cost_delta_minor,occurred_at,ingested_at,
   payload_hash,source_topic,source_partition,source_offset,projected_at)
VALUES
  (@tenant,'rcn-delta-conv-1h','rcn-fact-conv-1h','rcn-fact-conv-1h',1,'UPSERT','CONVERSION','rcn-order-confirmed',@campaign_active,@experiment,'VARIANT_A',@hash_sms,1,128800,2000,@t1h,@t1h,REPEAT('a',64),'mock.seed.retail-cn.extra',0,11,@now),
  (@tenant,'rcn-delta-conv-3h','rcn-fact-conv-3h','rcn-fact-conv-3h',1,'UPSERT','CONVERSION','rcn-order-sms','rcn-cmp-sms',@experiment,'CONTROL',@hash_sms,1,89900,0,@t3h,@t3h,REPEAT('b',64),'mock.seed.retail-cn.extra',0,12,@now),
  (@tenant,'rcn-delta-conv-6h','rcn-fact-conv-6h','rcn-fact-conv-6h',1,'UPSERT','CONVERSION','rcn-order-holdout',@campaign_active,@experiment,'CONTROL',@hash_wait,1,45900,0,@t6h,@t6h,REPEAT('c',64),'mock.seed.retail-cn.extra',0,13,@now),
  (@tenant,'rcn-delta-click-2h','rcn-fact-click-2h','rcn-fact-click-2h',1,'UPSERT','CLICK','rcn-contact-click',@campaign_active,@experiment,'VARIANT_A',@hash_sms,1,0,0,@t2h,@t2h,REPEAT('d',64),'mock.seed.retail-cn.extra',0,14,@now)
ON DUPLICATE KEY UPDATE fact_type=VALUES(fact_type), campaign_id=VALUES(campaign_id), count_delta=VALUES(count_delta),
  revenue_delta_minor=VALUES(revenue_delta_minor), cost_delta_minor=VALUES(cost_delta_minor), occurred_at=VALUES(occurred_at),
  ingested_at=VALUES(ingested_at), payload_hash=VALUES(payload_hash), projected_at=VALUES(projected_at);

INSERT INTO marketing_measurement.mk_attribution_credit
  (tenant_id,conversion_event_id,policy_name,touch_event_id,credit_value,revenue_minor,calculated_at)
VALUES
  (@tenant,'rcn-fact-conv-1h','LAST_TOUCH','rcn-fact-click-2h',1.00000000,128800,@now),
  (@tenant,'rcn-fact-conv-3h','LAST_TOUCH','rcn-contact-sms',1.00000000,89900,@now)
ON DUPLICATE KEY UPDATE credit_value=VALUES(credit_value), revenue_minor=VALUES(revenue_minor), calculated_at=VALUES(calculated_at);

INSERT INTO marketing_measurement.mk_decision_trace
  (tenant_id,trace_id,request_id,order_id,subject_hash,generation_no,duration_micros,candidates_json,pricing_json,
   terms_version,expires_at,legal_hold,created_at)
VALUES
  (@tenant,'rcn-trace-reserved','rcn-decision-reserved','rcn-order-reserved',@hash_wait,1842,640,
   JSON_OBJECT('rcn-fixed-20','RESERVED'), JSON_OBJECT('currency','CNY','discountMinor',2000,'applicationId','rcn-app-reserved'),
   'rcn-terms-review',@next_month,false,@t1h),
  (@tenant,'rcn-trace-confirmed','rcn-decision-confirmed','rcn-order-confirmed',@hash_sms,1842,710,
   JSON_OBJECT('rcn-fixed-20','APPLIED'), JSON_OBJECT('currency','CNY','discountMinor',2000,'applicationId','rcn-app-confirmed'),
   'rcn-terms-review',@next_month,false,@t2h)
ON DUPLICATE KEY UPDATE order_id=VALUES(order_id), subject_hash=VALUES(subject_hash), duration_micros=VALUES(duration_micros),
  candidates_json=VALUES(candidates_json), pricing_json=VALUES(pricing_json), created_at=VALUES(created_at);

-- 种子写入的预算账户发生在 Flyway V9 之后，必须补 16 路 escrow。
-- 这里不是数据库迁移：严格限定两个演示租户及约定资源，不能重分配其他账户的真实余额。
INSERT INTO marketing_benefit.mk_resource_escrow_bucket
  (tenant_id,resource_key,bucket_id,authorized_amount,available_amount,reserved_amount,
   consumed_amount,returned_amount,fencing_epoch,version_no,state_name,updated_at)
SELECT account.tenant_id, account.resource_key, bucket.bucket_id,
       (account.available_amount DIV 16)
         + IF(bucket.bucket_id < MOD(account.available_amount,16),1,0)
         + IF(bucket.bucket_id=0,account.reserved_amount+account.consumed_amount,0),
       (account.available_amount DIV 16)
         + IF(bucket.bucket_id < MOD(account.available_amount,16),1,0),
       IF(bucket.bucket_id=0,account.reserved_amount,0),
       IF(bucket.bucket_id=0,account.consumed_amount,0),
       IF(bucket.bucket_id=0,account.returned_amount,0),
       account.fencing_epoch,
       IF(bucket.bucket_id=0,account.version_no,0),
       account.state_name,
       account.updated_at
FROM marketing_benefit.mk_resource_account account
JOIN (
  SELECT 0 bucket_id UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
  UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
  UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
  UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
) bucket
WHERE account.resource_type='BUDGET'
  AND (
    (account.tenant_id IN ('marketing-platform', @tenant)
      AND account.resource_key IN ('BUDGET:PLATFORM:mock-platform:CNY', 'BUDGET:MERCHANT:mock-ha:CNY'))
    OR (account.tenant_id=@tenant AND account.resource_key IN (@budget_plat, @budget_shop))
  )
ON DUPLICATE KEY UPDATE
  authorized_amount=VALUES(authorized_amount), available_amount=VALUES(available_amount),
  reserved_amount=VALUES(reserved_amount), consumed_amount=VALUES(consumed_amount),
  returned_amount=VALUES(returned_amount), fencing_epoch=VALUES(fencing_epoch),
  version_no=VALUES(version_no), state_name=VALUES(state_name), updated_at=VALUES(updated_at);

INSERT INTO marketing_benefit.mk_reservation_escrow_allocation
  (tenant_id,application_id,resource_key,bucket_id,original_amount,reserved_amount,
   consumed_amount,refunded_amount,released_amount,reservation_epoch)
SELECT item.tenant_id,item.application_id,item.resource_key,0,item.original_amount,item.reserved_amount,
       item.consumed_amount,item.refunded_amount,item.released_amount,item.reservation_epoch
FROM marketing_benefit.mk_reservation_item item
JOIN marketing_benefit.mk_resource_account account
  ON account.tenant_id=item.tenant_id AND account.resource_key=item.resource_key
WHERE account.resource_type='BUDGET'
  AND item.tenant_id IN ('marketing-platform', @tenant)
  AND item.application_id='mock-pa-ha-001'
  AND item.resource_key IN ('BUDGET:PLATFORM:mock-platform:CNY', 'BUDGET:MERCHANT:mock-ha:CNY')
ON DUPLICATE KEY UPDATE original_amount=VALUES(original_amount), reserved_amount=VALUES(reserved_amount),
  consumed_amount=VALUES(consumed_amount), refunded_amount=VALUES(refunded_amount),
  released_amount=VALUES(released_amount), reservation_epoch=VALUES(reservation_epoch);

COMMIT;

SELECT 'rcn_campaigns' AS dataset, COUNT(*) AS row_count FROM marketing_control.mk_campaign WHERE tenant_id=@tenant
UNION ALL SELECT 'rcn_open_approvals', COUNT(*) FROM marketing_control.mk_approval_case WHERE tenant_id=@tenant AND status='OPEN'
UNION ALL SELECT 'rcn_benefits', COUNT(*) FROM marketing_benefit.mk_benefit_definition WHERE tenant_id=@tenant
UNION ALL SELECT 'rcn_enrollments', COUNT(*) FROM marketing_journey.mk_enrollment WHERE tenant_id=@tenant
UNION ALL SELECT 'rcn_contacts', COUNT(*) FROM marketing_engagement.mk_contact_attempt WHERE tenant_id=@tenant
UNION ALL SELECT 'rcn_award_intents', COUNT(*) FROM marketing_benefit.mk_award_intent_outbox WHERE tenant_id=@tenant
UNION ALL SELECT 'rcn_quarantine', COUNT(*) FROM marketing_events.mk_quarantine WHERE tenant_id=@tenant;
