-- Idempotent end-to-end system mock data for the local MySQL stack.
-- All records use stable "mock-*" identifiers so rerunning this file refreshes,
-- rather than duplicates, the demo scenario.

SET NAMES utf8mb4;
SET @tenant = 'marketing-platform';
SET @organization = 'marketing-platform';
SET @actor = 'system-mock-seeder';
SET @campaign = 'mock-cmp-ha-2026';
SET @offer_definition = 'mock-offer-ha-2026';
SET @journey_definition = 'mock-journey-cart-recall';
SET @audience = 'mock-aud-plus-ha';
SET @snapshot = 'mock-snap-plus-ha-v1';
SET @benefit = 'mock-coupon-ha-80';
SET @inventory = 'INVENTORY:mock-coupon-ha-80';
SET @platform_budget = 'BUDGET:PLATFORM:mock-platform:CNY';
SET @merchant_budget = 'BUDGET:MERCHANT:mock-ha:CNY';
SET @application = 'mock-pa-ha-001';
SET @order_id = 'mock-order-ha-001';
SET @subject = 'mock-subject-plus-001';
SET @subject_hash = SHA2(@subject, 256);
SET @experiment = 'mock-exp-ha-7';
SET @now = DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t1h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t2h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t3h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 3 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t4h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 4 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t5h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @t6h = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 6 HOUR), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @tomorrow = DATE_FORMAT(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @next_month = DATE_FORMAT(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY), '%Y-%m-%dT%H:%i:%s.%fZ');
SET @offer_hash = CONCAT('sha256:', REPEAT('1', 64));
SET @journey_hash = CONCAT('sha256:', REPEAT('2', 64));
SET @offer_checksum = CONCAT('sha256:', REPEAT('3', 64));
SET @journey_checksum = CONCAT('sha256:', REPEAT('4', 64));

START TRANSACTION;

-- 1. Control plane: campaign -> governed definitions -> approvals -> active releases.
INSERT INTO marketing_control.mk_campaign
  (tenant_id, campaign_id, name, objective, status, organization_id, shop_id, created_at, updated_at)
VALUES
  (@tenant, @campaign, '双11家电主会场', '验证受众、Offer、权益、发布、决策、旅程、触达与衡量的完整闭环', 'ACTIVE', @organization, NULL, @t6h, @now),
  (@tenant, 'mock-cmp-member-day', 'PLUS 超级会员日', '会员复购与客单提升', 'IN_REVIEW', @organization, NULL, @t5h, @t1h),
  (@tenant, 'mock-cmp-new-user', '新客首购礼', '新注册用户七日首购', 'DRAFT', @organization, NULL, @t4h, @t2h)
ON DUPLICATE KEY UPDATE
  name=VALUES(name), objective=VALUES(objective), status=VALUES(status),
  organization_id=VALUES(organization_id), shop_id=VALUES(shop_id), updated_at=VALUES(updated_at);

SET @offer_graph = JSON_OBJECT(
  'definitionId', @offer_definition,
  'dialect', 'OFFER_DECISION_DAG',
  'dialectVersion', '1.0.0',
  'nodes', JSON_ARRAY(
    JSON_OBJECT('id','start','stableTypeId','offer.start','semanticVersion','1.0.0','config',JSON_OBJECT()),
    JSON_OBJECT('id','scope','stableTypeId','offer.scope','semanticVersion','1.0.0','config',JSON_OBJECT('category','LARGE_APPLIANCE','shopIds','all-shops')),
    JSON_OBJECT('id','member','stableTypeId','offer.condition','semanticVersion','1.0.0','config',JSON_OBJECT('field','member.level','equals','PLUS','requiredAudienceSnapshotId',@audience)),
    JSON_OBJECT('id','discount','stableTypeId','offer.fixed','semanticVersion','1.0.0','config',JSON_OBJECT(
      'offerId','mock-fixed-ha-80','benefitDefinitionVersion',@benefit,'amountMinor','8000','thresholdMinor','50000',
      'validFrom','2025-01-01T00:00:00Z','validTo','2035-01-01T00:00:00Z','channels','APP',
      'shopIds','all-shops','fundingRules','PLATFORM|mock-platform|6000,MERCHANT|mock-ha|4000',
      'requiredAudienceSnapshotId',@audience,'reasonCode','MOCK_PLUS_HOME_APPLIANCE')),
    JSON_OBJECT('id','end','stableTypeId','offer.end','semanticVersion','1.0.0','config',JSON_OBJECT())
  ),
  'edges', JSON_ARRAY(
    JSON_OBJECT('id','offer-e1','sourceNodeId','start','sourcePort','next','targetNodeId','scope','targetPort','in'),
    JSON_OBJECT('id','offer-e2','sourceNodeId','scope','sourcePort','matched','targetNodeId','member','targetPort','in'),
    JSON_OBJECT('id','offer-e3','sourceNodeId','member','sourcePort','true','targetNodeId','discount','targetPort','in'),
    JSON_OBJECT('id','offer-e4','sourceNodeId','discount','sourcePort','next','targetNodeId','end','targetPort','in')
  ),
  'variables', JSON_OBJECT(),
  'annotations', JSON_OBJECT('terms','家电满 500 元减 80 元；平台与商家按 60/40 出资')
);

SET @journey_graph = JSON_OBJECT(
  'definitionId', @journey_definition,
  'dialect', 'JOURNEY_STATE_MACHINE',
  'dialectVersion', '1.0.0',
  'nodes', JSON_ARRAY(
    JSON_OBJECT('id','trigger','stableTypeId','journey.trigger','semanticVersion','1.0.0','config',JSON_OBJECT('eventType','CART_ADDED')),
    JSON_OBJECT('id','wait','stableTypeId','journey.wait_timer','semanticVersion','1.0.0','config',JSON_OBJECT('delaySeconds','1800')),
    JSON_OBJECT('id','send','stableTypeId','journey.send','semanticVersion','1.0.0','config',JSON_OBJECT(
      'channel','PUSH','templateId','mock-tpl-cart-push','templateVersion','1','campaignId',@campaign,
      'recipientToken','mock-device-token-001','timezone','Asia/Shanghai','personalized','true','name','PLUS 会员')),
    JSON_OBJECT('id','end','stableTypeId','journey.end','semanticVersion','1.0.0','config',JSON_OBJECT())
  ),
  'edges', JSON_ARRAY(
    JSON_OBJECT('id','journey-e1','sourceNodeId','trigger','sourcePort','next','targetNodeId','wait','targetPort','in'),
    JSON_OBJECT('id','journey-e2','sourceNodeId','wait','sourcePort','elapsed','targetNodeId','send','targetPort','in'),
    JSON_OBJECT('id','journey-e3','sourceNodeId','send','sourcePort','next','targetNodeId','end','targetPort','in')
  ),
  'variables', JSON_OBJECT('maxStepsPerSignal','20','maxIterations','3','stateTtlSeconds','259200'),
  'annotations', JSON_OBJECT('terms','加购未支付 30 分钟后发送召回 Push')
);

INSERT INTO marketing_control.mk_definition_version
  (tenant_id, definition_id, campaign_id, version_no, dialect, graph_json, semantic_hash, status, created_by, created_at, updated_at)
VALUES
  (@tenant, @offer_definition, @campaign, 1, 'OFFER_DECISION_DAG', @offer_graph, @offer_hash, 'APPROVED', @actor, @t5h, @t4h),
  (@tenant, @journey_definition, @campaign, 1, 'JOURNEY_STATE_MACHINE', @journey_graph, @journey_hash, 'APPROVED', @actor, @t4h, @t3h)
ON DUPLICATE KEY UPDATE
  campaign_id=VALUES(campaign_id), dialect=VALUES(dialect), graph_json=VALUES(graph_json),
  semantic_hash=VALUES(semantic_hash), status=VALUES(status), created_by=VALUES(created_by), updated_at=VALUES(updated_at);

INSERT INTO marketing_control.mk_approval_case
  (tenant_id, case_id, definition_id, definition_version, submitted_by, required_roles, status, created_at, updated_at)
VALUES
  (@tenant, 'mock-apr-offer-001', @offer_definition, 1, @actor, 'BUSINESS,COMPLIANCE,FINANCE', 'APPROVED', @t5h, @t4h),
  (@tenant, 'mock-apr-journey-001', @journey_definition, 1, @actor, 'BUSINESS,COMPLIANCE', 'APPROVED', @t4h, @t3h)
ON DUPLICATE KEY UPDATE
  definition_id=VALUES(definition_id), definition_version=VALUES(definition_version), submitted_by=VALUES(submitted_by),
  required_roles=VALUES(required_roles), status=VALUES(status), updated_at=VALUES(updated_at);

-- Remove the obsolete role name from seed versions created before the mock
-- scenario was aligned with ApprovalCase.Role. Only stable mock case IDs match.
DELETE FROM marketing_control.mk_approval_decision
WHERE tenant_id=@tenant AND case_id IN ('mock-apr-offer-001','mock-apr-journey-001') AND role_name='RISK';

INSERT INTO marketing_control.mk_approval_decision
  (tenant_id, case_id, role_name, actor_id, decided_at)
VALUES
  (@tenant, 'mock-apr-offer-001', 'BUSINESS', 'mock-approver-business', @t4h),
  (@tenant, 'mock-apr-offer-001', 'FINANCE', 'mock-approver-finance', @t4h),
  (@tenant, 'mock-apr-offer-001', 'COMPLIANCE', 'mock-approver-compliance', @t4h),
  (@tenant, 'mock-apr-journey-001', 'BUSINESS', 'mock-journey-business', @t3h),
  (@tenant, 'mock-apr-journey-001', 'COMPLIANCE', 'mock-journey-compliance', @t3h)
ON DUPLICATE KEY UPDATE actor_id=VALUES(actor_id), decided_at=VALUES(decided_at);

INSERT INTO marketing_control.mk_terms_snapshot
  (tenant_id, terms_id, definition_id, definition_version, content_json, content_hash, created_at)
VALUES
  (@tenant, 'mock-terms-offer-001', @offer_definition, 1,
   JSON_OBJECT('terms','家电满 500 元减 80 元','funding','PLATFORM:60,MERCHANT:40','benefitId',@benefit), CONCAT('sha256:',REPEAT('5',64)), @t4h),
  (@tenant, 'mock-terms-journey-001', @journey_definition, 1,
   JSON_OBJECT('terms','加购未支付召回','channel','PUSH','templateId','mock-tpl-cart-push'), CONCAT('sha256:',REPEAT('6',64)), @t3h)
ON DUPLICATE KEY UPDATE content_json=VALUES(content_json), content_hash=VALUES(content_hash), created_at=VALUES(created_at);

-- Compiler artifacts provide the provenance referenced by both release manifests.
INSERT INTO marketing_compiler.mk_compiled_artifact
  (tenant_id, artifact_id, definition_id, definition_version, type_name, abi, payload, checksum, source_digest,
   signature_key_id, signature_value, metadata_json, compiled_at)
VALUES
  (@tenant, 'mock-artifact-offer-v1', @offer_definition, 1, 'OFFER_POLICY', 'offer-policy-v1',
   CONVERT(@offer_graph USING utf8mb4), @offer_checksum, @offer_hash, 'mock-release-key', 'mock-signature-offer',
   JSON_OBJECT('campaignId',@campaign,'mock',true), @t4h),
  (@tenant, 'mock-artifact-journey-v1', @journey_definition, 1, 'JOURNEY_PLAN', 'journey-plan-v1',
   CONVERT(@journey_graph USING utf8mb4), @journey_checksum, @journey_hash, 'mock-release-key', 'mock-signature-journey',
   JSON_OBJECT('campaignId',@campaign,'mock',true), @t3h)
ON DUPLICATE KEY UPDATE
  definition_id=VALUES(definition_id), definition_version=VALUES(definition_version), type_name=VALUES(type_name),
  abi=VALUES(abi), payload=VALUES(payload), checksum=VALUES(checksum), source_digest=VALUES(source_digest),
  signature_key_id=VALUES(signature_key_id), signature_value=VALUES(signature_value), metadata_json=VALUES(metadata_json), compiled_at=VALUES(compiled_at);

SET @offer_manifest = JSON_OBJECT(
  'manifestId','mock-manifest-decision-east','tenantId',JSON_OBJECT('value',@tenant),
  'environment','local','cell','cell-a','runtime','decision','namespace','main','generation',1842,'stableGeneration',1842,
  'canaryGenerations',JSON_ARRAY(),'retainedGenerations',JSON_ARRAY(1841),
  'artifacts',JSON_ARRAY(JSON_OBJECT(
    'artifactId','mock-artifact-offer-v1','type','OFFER_POLICY','uri','compiler://mock-artifact-offer-v1',
    'checksum',@offer_checksum,'sourceDigest',@offer_hash,'signatureKeyId','mock-release-key','signature','mock-signature-offer',
    'abi','offer-policy-v1','definitionId',@offer_definition,'definitionVersion',1)),
  'schemaVersions',JSON_OBJECT('terms','terms-v1'),'canaryBasisPoints',0,'activationAt',@t3h,'expiresAt',@next_month,
  'createdBy',@actor,'approvalCaseIds',JSON_ARRAY('mock-apr-offer-001'),'createdAt',@t3h,'signature','mock-manifest-signature-offer'
);
SET @journey_manifest = JSON_OBJECT(
  'manifestId','mock-manifest-journey-east','tenantId',JSON_OBJECT('value',@tenant),
  'environment','local','cell','cell-a','runtime','journey','namespace','main','generation',731,'stableGeneration',731,
  'canaryGenerations',JSON_ARRAY(),'retainedGenerations',JSON_ARRAY(730),
  'artifacts',JSON_ARRAY(JSON_OBJECT(
    'artifactId','mock-artifact-journey-v1','type','JOURNEY_PLAN','uri','compiler://mock-artifact-journey-v1',
    'checksum',@journey_checksum,'sourceDigest',@journey_hash,'signatureKeyId','mock-release-key','signature','mock-signature-journey',
    'abi','journey-plan-v1','definitionId',@journey_definition,'definitionVersion',1)),
  'schemaVersions',JSON_OBJECT('terms','terms-v1'),'canaryBasisPoints',0,'activationAt',@t2h,'expiresAt',@next_month,
  'createdBy',@actor,'approvalCaseIds',JSON_ARRAY('mock-apr-journey-001'),'createdAt',@t2h,'signature','mock-manifest-signature-journey'
);

INSERT INTO marketing_control.mk_release_slot
  (tenant_id, environment_name, cell_id, runtime_name, namespace_name, stable_generation, desired_generation, latest_generation, activation_sequence, updated_at)
VALUES
  (@tenant,'local','cell-a','decision','main',1842,1842,1842,21,@now),
  (@tenant,'local','cell-a','journey','main',731,731,731,9,@now)
ON DUPLICATE KEY UPDATE stable_generation=VALUES(stable_generation), desired_generation=VALUES(desired_generation),
  latest_generation=VALUES(latest_generation), activation_sequence=VALUES(activation_sequence), updated_at=VALUES(updated_at);

INSERT INTO marketing_control.mk_release_manifest
  (tenant_id, manifest_id, environment_name, cell_id, runtime_name, namespace_name, generation_no, state_name, manifest_json, created_at)
VALUES
  (@tenant,'mock-manifest-decision-east','local','cell-a','decision','main',1842,'ACTIVE',@offer_manifest,@t3h),
  (@tenant,'mock-manifest-journey-east','local','cell-a','journey','main',731,'ACTIVE',@journey_manifest,@t2h)
ON DUPLICATE KEY UPDATE state_name=VALUES(state_name), manifest_json=VALUES(manifest_json), created_at=VALUES(created_at);

INSERT INTO marketing_control.mk_runtime_ack
  (tenant_id, manifest_id, runtime_id, status_name, build_digest, supported_abis, warmed_artifact_ids, capacity_value,
   acknowledged_at, signature_key_id, signature_value)
VALUES
  (@tenant,'mock-manifest-decision-east','mock-decision-runtime-a','READY','mock-build-decision','offer-policy-v1','mock-artifact-offer-v1',400,@now,'mock-runtime-key','mock-ack-signature'),
  (@tenant,'mock-manifest-journey-east','mock-journey-runtime-a','READY','mock-build-journey','journey-plan-v1','mock-artifact-journey-v1',400,@now,'mock-runtime-key','mock-ack-signature')
ON DUPLICATE KEY UPDATE status_name=VALUES(status_name), build_digest=VALUES(build_digest), supported_abis=VALUES(supported_abis),
  warmed_artifact_ids=VALUES(warmed_artifact_ids), capacity_value=VALUES(capacity_value), acknowledged_at=VALUES(acknowledged_at),
  signature_key_id=VALUES(signature_key_id), signature_value=VALUES(signature_value);

-- 2. Audience: governed field -> segment -> materialized snapshot -> member.
INSERT INTO marketing_audience.mk_field_definition
  (tenant_id, field_id, value_type, owner_name, provenance, classification, allowed_uses, max_age_seconds,
   null_policy, missing_policy, retention_days, created_at)
VALUES
  (@tenant,'member.score','DECIMAL','crm-profile','crm.member_profile.score','INTERNAL','ELIGIBILITY,PERSONALIZATION',86400,'NO_MATCH','NO_MATCH',365,@t6h),
  (@tenant,'member.level','STRING','crm-profile','crm.member_profile.level','INTERNAL','ELIGIBILITY,PERSONALIZATION',86400,'NO_MATCH','NO_MATCH',365,@t6h)
ON DUPLICATE KEY UPDATE value_type=VALUES(value_type), owner_name=VALUES(owner_name), provenance=VALUES(provenance),
  classification=VALUES(classification), allowed_uses=VALUES(allowed_uses), max_age_seconds=VALUES(max_age_seconds),
  null_policy=VALUES(null_policy), missing_policy=VALUES(missing_policy), retention_days=VALUES(retention_days);

SET @audience_rule = JSON_OBJECT('match','ALL','conditions',JSON_ARRAY(
  JSON_OBJECT('fieldId','member.score','operator','GTE','value','80'),
  JSON_OBJECT('fieldId','member.level','operator','EQ','value','PLUS')));
INSERT INTO marketing_audience.mk_segment_definition
  (tenant_id, segment_id, version_no, name, rule_json, rule_hash, state_name, created_by, created_at)
VALUES
  (@tenant,@audience,1,'高意向 PLUS 家电人群',@audience_rule,CONCAT('sha256:',REPEAT('7',64)),'ACTIVE',@actor,@t5h)
ON DUPLICATE KEY UPDATE name=VALUES(name), rule_json=VALUES(rule_json), rule_hash=VALUES(rule_hash),
  state_name=VALUES(state_name), created_by=VALUES(created_by), created_at=VALUES(created_at);

INSERT INTO marketing_audience.mk_audience_snapshot
  (tenant_id, snapshot_id, segment_id, segment_version, as_of_time, watermark_time, expires_at, member_count, checksum, state_name, created_at)
VALUES
  (@tenant,@snapshot,@audience,1,@t4h,@t4h,@next_month,128420,CONCAT('sha256:',REPEAT('8',64)),'READY',@t4h)
ON DUPLICATE KEY UPDATE segment_id=VALUES(segment_id), segment_version=VALUES(segment_version), as_of_time=VALUES(as_of_time),
  watermark_time=VALUES(watermark_time), expires_at=VALUES(expires_at), member_count=VALUES(member_count), checksum=VALUES(checksum),
  state_name=VALUES(state_name), created_at=VALUES(created_at);

INSERT INTO marketing_audience.mk_audience_member
  (tenant_id, snapshot_id, subject_hash, membership_version, active_value, updated_at)
VALUES (@tenant,@snapshot,@subject_hash,1,true,@now)
ON DUPLICATE KEY UPDATE membership_version=VALUES(membership_version), active_value=VALUES(active_value), updated_at=VALUES(updated_at);

INSERT INTO marketing_decision.mk_audience_membership_projection
  (tenant_id, audience_id, subject_hash, member_value, membership_version, expires_at, updated_at)
VALUES (@tenant,@audience,@subject_hash,true,1,@next_month,@now)
ON DUPLICATE KEY UPDATE member_value=VALUES(member_value), membership_version=VALUES(membership_version),
  expires_at=VALUES(expires_at), updated_at=VALUES(updated_at);

-- 3. Benefit funding: catalog -> conserved resource accounts -> confirmed application -> ledger.
INSERT INTO marketing_benefit.mk_resource_account
  (tenant_id, resource_key, resource_type, currency_code, authorized_amount, available_amount, reserved_amount,
   consumed_amount, returned_amount, fencing_epoch, version_no, state_name, updated_at)
VALUES
  (@tenant,@inventory,'INVENTORY','UNIT',1000000,999999,0,1,0,42,2,'ACTIVE',@now),
  (@tenant,@platform_budget,'BUDGET','CNY',1200000000,1199995200,0,4800,0,42,2,'ACTIVE',@now),
  (@tenant,@merchant_budget,'BUDGET','CNY',800000000,799996800,0,3200,0,42,2,'ACTIVE',@now)
ON DUPLICATE KEY UPDATE resource_type=VALUES(resource_type), currency_code=VALUES(currency_code),
  authorized_amount=VALUES(authorized_amount), available_amount=VALUES(available_amount), reserved_amount=VALUES(reserved_amount),
  consumed_amount=VALUES(consumed_amount), returned_amount=VALUES(returned_amount), fencing_epoch=VALUES(fencing_epoch),
  version_no=VALUES(version_no), state_name=VALUES(state_name), updated_at=VALUES(updated_at);

SET @benefit_policy = JSON_OBJECT(
  'type','COUPON','thresholdMinor',50000,'discountMinor',8000,
  'scope','category:LARGE_APPLIANCE AND shop:all-shops','validity','P7D',
  'platformShare',60,'merchantShare',40,'cancelUnlock',true,'partialRefund',true,
  'fullRefundReturn',true,'reverseLedger',true,'expireReturn',true);
INSERT INTO marketing_benefit.mk_benefit_definition
  (tenant_id, benefit_id, version_no, name_text, status_name, resource_key, policy_json, created_by, created_at)
VALUES (@tenant,@benefit,1,'家电满 500 减 80 券','ACTIVE',@inventory,@benefit_policy,@actor,@t5h)
ON DUPLICATE KEY UPDATE name_text=VALUES(name_text), status_name=VALUES(status_name), resource_key=VALUES(resource_key),
  policy_json=VALUES(policy_json), created_by=VALUES(created_by), created_at=VALUES(created_at);
INSERT INTO marketing_benefit.mk_benefit_definition_head (tenant_id,benefit_id,latest_version)
VALUES (@tenant,@benefit,1)
ON DUPLICATE KEY UPDATE latest_version=VALUES(latest_version);

INSERT INTO marketing_benefit.mk_promotion_application
  (tenant_id, application_id, quote_id, decision_request_id, order_id, organization_id, shop_ids_json,
   cart_digest, token_digest, generation_no, state_name, total_discount, currency_code, expires_at,
   created_at, updated_at, expiry_attempts, expiry_next_attempt_at, expiry_last_error)
VALUES
  (@tenant,@application,'mock-quote-ha-001','mock-decision-ha-001',@order_id,@organization,JSON_ARRAY(),
   CONCAT('sha256:',REPEAT('9',64)),REPEAT('a',64),1842,'CONFIRMED',8000,'CNY',@tomorrow,@t2h,@t1h,0,@tomorrow,'')
ON DUPLICATE KEY UPDATE decision_request_id=VALUES(decision_request_id), order_id=VALUES(order_id), organization_id=VALUES(organization_id),
  shop_ids_json=VALUES(shop_ids_json), cart_digest=VALUES(cart_digest), token_digest=VALUES(token_digest), generation_no=VALUES(generation_no),
  state_name=VALUES(state_name), total_discount=VALUES(total_discount), currency_code=VALUES(currency_code), expires_at=VALUES(expires_at),
  updated_at=VALUES(updated_at), expiry_attempts=VALUES(expiry_attempts), expiry_next_attempt_at=VALUES(expiry_next_attempt_at), expiry_last_error=VALUES(expiry_last_error);

INSERT INTO marketing_benefit.mk_reservation_item
  (tenant_id, application_id, resource_key, resource_type, currency_code, original_amount, reserved_amount,
   consumed_amount, refunded_amount, released_amount, reservation_epoch)
VALUES
  (@tenant,@application,@inventory,'INVENTORY','UNIT',1,0,1,0,0,42),
  (@tenant,@application,@platform_budget,'BUDGET','CNY',4800,0,4800,0,0,42),
  (@tenant,@application,@merchant_budget,'BUDGET','CNY',3200,0,3200,0,0,42)
ON DUPLICATE KEY UPDATE resource_type=VALUES(resource_type), currency_code=VALUES(currency_code), original_amount=VALUES(original_amount),
  reserved_amount=VALUES(reserved_amount), consumed_amount=VALUES(consumed_amount), refunded_amount=VALUES(refunded_amount),
  released_amount=VALUES(released_amount), reservation_epoch=VALUES(reservation_epoch);

INSERT INTO marketing_benefit.mk_funding_ledger
  (tenant_id, ledger_id, application_id, order_id, resource_key, operation_name, debit_bucket, credit_bucket,
   amount_value, account_version, correction_of, occurred_at)
VALUES
  (@tenant,'mock-ledger-inventory-reserve',@application,@order_id,@inventory,'RESERVE','AVAILABLE','RESERVED',1,1,NULL,@t2h),
  (@tenant,'mock-ledger-inventory-confirm',@application,@order_id,@inventory,'CONFIRM','RESERVED','CONSUMED',1,2,NULL,@t1h),
  (@tenant,'mock-ledger-platform-reserve',@application,@order_id,@platform_budget,'RESERVE','AVAILABLE','RESERVED',4800,1,NULL,@t2h),
  (@tenant,'mock-ledger-platform-confirm',@application,@order_id,@platform_budget,'CONFIRM','RESERVED','CONSUMED',4800,2,NULL,@t1h),
  (@tenant,'mock-ledger-merchant-reserve',@application,@order_id,@merchant_budget,'RESERVE','AVAILABLE','RESERVED',3200,1,NULL,@t2h),
  (@tenant,'mock-ledger-merchant-confirm',@application,@order_id,@merchant_budget,'CONFIRM','RESERVED','CONSUMED',3200,2,NULL,@t1h)
ON DUPLICATE KEY UPDATE application_id=VALUES(application_id), order_id=VALUES(order_id), resource_key=VALUES(resource_key),
  operation_name=VALUES(operation_name), debit_bucket=VALUES(debit_bucket), credit_bucket=VALUES(credit_bucket),
  amount_value=VALUES(amount_value), account_version=VALUES(account_version), correction_of=VALUES(correction_of), occurred_at=VALUES(occurred_at);

INSERT INTO marketing_benefit.mk_benefit_outbox
  (tenant_id,event_id,aggregate_id,event_type,destination_topic,partition_key,stream_sequence,payload_json,
   publish_attempts,next_attempt_at,last_error,created_at,published_at,dead_lettered_at)
VALUES
  (@tenant,'mock-benefit-event-reserved',@application,'PromotionReserved','mk.benefit.event.v1',CONCAT(@tenant,':',@application),1,
   JSON_OBJECT('eventId','mock-benefit-event-reserved','eventType','PromotionReserved','tenantId',@tenant,'aggregateId',@application,'occurredAt',@t2h),0,@t2h,'',@t2h,@t2h,NULL),
  (@tenant,'mock-benefit-event-confirmed',@application,'PromotionCONFIRMED','mk.benefit.event.v1',CONCAT(@tenant,':',@application),2,
   JSON_OBJECT('eventId','mock-benefit-event-confirmed','eventType','PromotionCONFIRMED','tenantId',@tenant,'aggregateId',@application,'occurredAt',@t1h),0,@t1h,'',@t1h,@t1h,NULL)
ON DUPLICATE KEY UPDATE event_type=VALUES(event_type), payload_json=VALUES(payload_json), published_at=VALUES(published_at), created_at=VALUES(created_at);
INSERT INTO marketing_benefit.mk_benefit_outbox_position (tenant_id,aggregate_id,last_sequence,updated_at)
VALUES (@tenant,@application,2,@now)
ON DUPLICATE KEY UPDATE last_sequence=GREATEST(last_sequence,VALUES(last_sequence)), updated_at=VALUES(updated_at);

-- 4. Event gateway: accepted profile/journey/fact events plus one quarantined example.
INSERT INTO marketing_events.mk_source_registration
  (tenant_id,source_id,source_uri,source_uri_hash,allowed_types,schema_versions,max_lateness_seconds,enabled_value,created_at)
VALUES (@tenant,'mock-source-commerce','urn:marketing:mock:commerce',SHA2('urn:marketing:mock:commerce',256),
  'PROFILE_CHANGED,JOURNEY_SIGNAL,MARKETING_FACT','1.0.0',3600,true,@t6h)
ON DUPLICATE KEY UPDATE allowed_types=VALUES(allowed_types), schema_versions=VALUES(schema_versions),
  max_lateness_seconds=VALUES(max_lateness_seconds), enabled_value=VALUES(enabled_value);

INSERT INTO marketing_events.mk_event_receipt
  (tenant_id,receipt_id,source_id,event_id,event_type,business_key,aggregate_version,status_name,reason_code,
   payload_hash,event_json,occurred_at,ingested_at)
VALUES
  (@tenant,'mock-receipt-profile','mock-source-commerce','mock-profile-changed-001','PROFILE_CHANGED',@subject,1,'ACCEPTED','',REPEAT('b',64),
   JSON_OBJECT('eventId','mock-profile-changed-001','sourceId','mock-source-commerce','eventType','PROFILE_CHANGED','businessKey',@subject,
     'subjectToken',@subject,'occurredAt',@t4h,'schemaVersion','1.0.0','data',JSON_OBJECT('profileVersion',1,'segmentIds',JSON_ARRAY(@audience))),@t4h,@t4h),
  (@tenant,'mock-receipt-journey','mock-source-commerce','mock-journey-start-001','JOURNEY_SIGNAL','mock-enr-cart-001',1,'ACCEPTED','',REPEAT('c',64),
   JSON_OBJECT('eventId','mock-journey-start-001','sourceId','mock-source-commerce','eventType','JOURNEY_SIGNAL','businessKey','mock-enr-cart-001',
     'subjectToken',@subject,'occurredAt',@t3h,'schemaVersion','1.0.0','data',JSON_OBJECT('journeyId',@journey_definition,'journeyVersion',1)),@t3h,@t3h),
  (@tenant,'mock-receipt-fact','mock-source-commerce','mock-conversion-ha-001','MARKETING_FACT',@order_id,1,'ACCEPTED','',REPEAT('d',64),
   JSON_OBJECT('eventId','mock-conversion-ha-001','sourceId','mock-source-commerce','eventType','MARKETING_FACT','businessKey',@order_id,
     'subjectToken',@subject,'occurredAt',@t1h,'schemaVersion','1.0.0','data',JSON_OBJECT('factType','CONVERSION','attributes',JSON_OBJECT('campaignId',@campaign,'revenueMinor','579900'))),@t1h,@t1h),
  (@tenant,'mock-receipt-bad','mock-source-commerce','mock-invalid-event-001','UNKNOWN_EVENT','mock-bad-001',NULL,'QUARANTINED','EVENT_TYPE_NOT_ALLOWED',REPEAT('e',64),
   JSON_OBJECT('eventId','mock-invalid-event-001','eventType','UNKNOWN_EVENT','note','用于运维隔离区演示'),@t2h,@t2h)
ON DUPLICATE KEY UPDATE event_type=VALUES(event_type), business_key=VALUES(business_key), aggregate_version=VALUES(aggregate_version),
  status_name=VALUES(status_name), reason_code=VALUES(reason_code), payload_hash=VALUES(payload_hash), event_json=VALUES(event_json),
  occurred_at=VALUES(occurred_at), ingested_at=VALUES(ingested_at);

INSERT INTO marketing_events.mk_quarantine
  (tenant_id,quarantine_id,receipt_id,reason_code,event_json,state_name,created_at,replayed_at)
VALUES (@tenant,'mock-quarantine-001','mock-receipt-bad','EVENT_TYPE_NOT_ALLOWED',
  JSON_OBJECT('eventId','mock-invalid-event-001','eventType','UNKNOWN_EVENT','note','修正类型后可重放'),'PENDING',@t2h,NULL)
ON DUPLICATE KEY UPDATE reason_code=VALUES(reason_code), event_json=VALUES(event_json), state_name=VALUES(state_name), created_at=VALUES(created_at), replayed_at=VALUES(replayed_at);

INSERT INTO marketing_events.mk_event_sequence
  (tenant_id,source_id,business_key,last_version,updated_at)
VALUES
  (@tenant,'mock-source-commerce',@subject,1,@t4h),
  (@tenant,'mock-source-commerce','mock-enr-cart-001',1,@t3h),
  (@tenant,'mock-source-commerce',@order_id,1,@t1h)
ON DUPLICATE KEY UPDATE last_version=GREATEST(last_version,VALUES(last_version)),updated_at=VALUES(updated_at);

INSERT INTO marketing_events.mk_event_outbox
  (tenant_id,outbox_id,receipt_id,event_type,destination_topic,partition_key,stream_sequence,payload_json,
   created_at,published_at,dead_lettered_at,publish_attempts,next_attempt_at,last_error)
VALUES
  (@tenant,'mock-outbox-profile','mock-receipt-profile','PROFILE_CHANGED','mk.profile.change.v1',CONCAT(@tenant,':',@subject),1,
   JSON_OBJECT('tenantId',@tenant,'subjectToken',@subject,'profileVersion',1,'segmentIds',JSON_ARRAY(@audience),'occurredAtEpochMillis',CAST(UNIX_TIMESTAMP(DATE_SUB(UTC_TIMESTAMP(),INTERVAL 4 HOUR))*1000 AS UNSIGNED)),@t4h,@t4h,NULL,0,@t4h,''),
  (@tenant,'mock-outbox-journey','mock-receipt-journey','JOURNEY_SIGNAL','mk.journey.signal.v1',CONCAT(@tenant,':mock-enr-cart-001'),1,
   JSON_OBJECT('tenantId',@tenant,'enrollmentId','mock-enr-cart-001','subjectToken',@subject,'planReference',JSON_OBJECT('artifactId','mock-artifact-journey-v1','generation',731,'journeyId',@journey_definition,'journeyVersion',1),'signal',JSON_OBJECT('type','START','signalId','mock-journey-start-001')),@t3h,@t3h,NULL,0,@t3h,''),
  (@tenant,'mock-outbox-fact','mock-receipt-fact','MARKETING_FACT','mk.marketing.fact.v1',CONCAT(@tenant,':mock-conversion-ha-001'),1,
   JSON_OBJECT('eventId','mock-conversion-ha-001','tenantId',@tenant,'type','CONVERSION','businessKey',@order_id,'subjectToken',@subject,'occurredAt',@t1h,'ingestedAt',@t1h,'schemaVersion','1.0.0','attributes',JSON_OBJECT('campaignId',@campaign,'revenueMinor','579900'),'correctionOf','','correctionRootId','mock-conversion-ha-001'),@t1h,@t1h,NULL,0,@t1h,'')
ON DUPLICATE KEY UPDATE event_type=VALUES(event_type),destination_topic=VALUES(destination_topic),partition_key=VALUES(partition_key),
  stream_sequence=VALUES(stream_sequence),payload_json=VALUES(payload_json),created_at=VALUES(created_at),published_at=VALUES(published_at),
  dead_lettered_at=VALUES(dead_lettered_at),publish_attempts=VALUES(publish_attempts),next_attempt_at=VALUES(next_attempt_at),last_error=VALUES(last_error);

INSERT INTO marketing_events.mk_event_stream_position
  (tenant_id,destination_topic,partition_key,last_sequence,updated_at)
VALUES
  (@tenant,'mk.profile.change.v1',CONCAT(@tenant,':',@subject),1,@now),
  (@tenant,'mk.journey.signal.v1',CONCAT(@tenant,':mock-enr-cart-001'),1,@now),
  (@tenant,'mk.marketing.fact.v1',CONCAT(@tenant,':mock-conversion-ha-001'),1,@now)
ON DUPLICATE KEY UPDATE last_sequence=GREATEST(last_sequence,VALUES(last_sequence)),updated_at=VALUES(updated_at);

-- 5. Journey and engagement: active plan -> completed enrollment -> delivered Push.
SET @journey_plan = JSON_OBJECT(
  'journeyId',@journey_definition,'version',1,'startNodeId','trigger',
  'nodes',JSON_OBJECT(
    'trigger',JSON_OBJECT('id','trigger','type','TRIGGER','config',JSON_OBJECT('eventType','CART_ADDED'),'routes',JSON_OBJECT('next','wait')),
    'wait',JSON_OBJECT('id','wait','type','WAIT_TIMER','config',JSON_OBJECT('delaySeconds','1800'),'routes',JSON_OBJECT('elapsed','send')),
    'send',JSON_OBJECT('id','send','type','SEND','config',JSON_OBJECT('channel','PUSH','templateId','mock-tpl-cart-push','templateVersion','1','campaignId',@campaign),'routes',JSON_OBJECT('next','end')),
    'end',JSON_OBJECT('id','end','type','END','config',JSON_OBJECT(),'routes',JSON_OBJECT())),
  'maxStepsPerSignal',20,'maxIterations',3,'stateTtl','PT72H');
INSERT INTO marketing_journey.mk_journey_definition
  (tenant_id,journey_id,version_no,plan_json,state_name,created_by,created_at)
VALUES (@tenant,@journey_definition,1,@journey_plan,'ACTIVE',@actor,@t3h)
ON DUPLICATE KEY UPDATE plan_json=VALUES(plan_json),state_name=VALUES(state_name),created_by=VALUES(created_by),created_at=VALUES(created_at);

SET @enrollment_snapshot = JSON_OBJECT(
  'tenantId',@tenant,'enrollmentId','mock-enr-cart-001','subjectToken',@subject,'journeyId',@journey_definition,
  'journeyVersion',1,'currentNodeId','end','status','COMPLETED','variables',JSON_OBJECT('campaignId',@campaign,'applicationId',@application),
  'processedSignalIds',JSON_ARRAY('mock-journey-start-001','mock-timer-fired-001'),
  'iterations',JSON_OBJECT(),'nodeExecutions',JSON_OBJECT('trigger',1,'wait',1,'send',1,'end',1),'updatedAt',@t1h);
INSERT INTO marketing_journey.mk_enrollment
  (tenant_id,enrollment_id,journey_id,journey_version,subject_token,trigger_event_id,status_name,current_node_id,
   snapshot_json,projection_topic,projection_partition,projection_offset,created_at,updated_at)
VALUES (@tenant,'mock-enr-cart-001',@journey_definition,1,@subject,'mock-journey-start-001','COMPLETED','end',
  @enrollment_snapshot,'mk.journey.output.v1',0,42,@t3h,@t1h)
ON DUPLICATE KEY UPDATE status_name=VALUES(status_name),current_node_id=VALUES(current_node_id),snapshot_json=VALUES(snapshot_json),
  projection_topic=VALUES(projection_topic),projection_partition=VALUES(projection_partition),projection_offset=VALUES(projection_offset),updated_at=VALUES(updated_at);

INSERT INTO marketing_journey.mk_node_effect_intent
  (tenant_id,command_id,enrollment_id,node_id,effect_type,payload_json,state_name,created_at)
VALUES (@tenant,'mock-cmd-push-001','mock-enr-cart-001','send','SEND',
  JSON_OBJECT('campaignId',@campaign,'channel','PUSH','templateId','mock-tpl-cart-push','templateVersion',1,'subjectToken',@subject),'DISPATCHED',@t1h)
ON DUPLICATE KEY UPDATE payload_json=VALUES(payload_json),state_name=VALUES(state_name),created_at=VALUES(created_at);

INSERT INTO marketing_journey.mk_journey_dispatch_position
  (tenant_id,enrollment_id,last_sequence,updated_at)
VALUES (@tenant,'mock-enr-cart-001',1,@now)
ON DUPLICATE KEY UPDATE last_sequence=GREATEST(last_sequence,VALUES(last_sequence)),updated_at=VALUES(updated_at);
INSERT INTO marketing_journey.mk_journey_dispatch_outbox
  (tenant_id,outbox_id,command_id,enrollment_id,destination_topic,partition_key,stream_sequence,payload_json,
   publish_attempts,next_attempt_at,last_error,created_at,published_at,dead_lettered_at)
VALUES (@tenant,'mock-journey-dispatch-001','mock-cmd-push-001','mock-enr-cart-001','mk.journey.command.v1',
  CONCAT(@tenant,':mock-enr-cart-001'),1,JSON_OBJECT('commandId','mock-cmd-push-001','enrollmentId','mock-enr-cart-001','effectType','SEND'),
  0,@t1h,'',@t1h,@t1h,NULL)
ON DUPLICATE KEY UPDATE destination_topic=VALUES(destination_topic),partition_key=VALUES(partition_key),stream_sequence=VALUES(stream_sequence),
  payload_json=VALUES(payload_json),publish_attempts=VALUES(publish_attempts),next_attempt_at=VALUES(next_attempt_at),last_error=VALUES(last_error),
  created_at=VALUES(created_at),published_at=VALUES(published_at),dead_lettered_at=VALUES(dead_lettered_at);
INSERT INTO marketing_journey.mk_journey_output_receipt
  (source_topic,source_partition,source_offset,tenant_id,enrollment_id,event_type,payload_hash,consumed_at)
VALUES (CONCAT('mock.journey.output.',@tenant),0,1,@tenant,'mock-enr-cart-001','ENROLLMENT_COMPLETED',REPEAT('f',64),@t1h)
ON DUPLICATE KEY UPDATE tenant_id=VALUES(tenant_id),enrollment_id=VALUES(enrollment_id),event_type=VALUES(event_type),payload_hash=VALUES(payload_hash),consumed_at=VALUES(consumed_at);

INSERT INTO marketing_engagement.mk_consent
  (tenant_id,subject_token,channel_name,allowed_value,minor_value,personalization_allowed,version_no,source_name,effective_at,updated_at)
VALUES (@tenant,@subject,'PUSH',true,false,true,1,'system-mock',@t6h,@now)
ON DUPLICATE KEY UPDATE allowed_value=VALUES(allowed_value),minor_value=VALUES(minor_value),personalization_allowed=VALUES(personalization_allowed),
  version_no=VALUES(version_no),source_name=VALUES(source_name),effective_at=VALUES(effective_at),updated_at=VALUES(updated_at);
INSERT INTO marketing_engagement.mk_frequency_policy
  (tenant_id,campaign_id,channel_name,window_seconds,max_contacts,quiet_start,quiet_end,updated_at)
VALUES (@tenant,@campaign,'PUSH',86400,3,'23:00:00','07:00:00',@now)
ON DUPLICATE KEY UPDATE window_seconds=VALUES(window_seconds),max_contacts=VALUES(max_contacts),quiet_start=VALUES(quiet_start),quiet_end=VALUES(quiet_end),updated_at=VALUES(updated_at);
INSERT INTO marketing_engagement.mk_template_version
  (tenant_id,template_id,version_no,channel_name,content_text,required_variables,state_name,created_by,created_at)
VALUES (@tenant,'mock-tpl-cart-push',1,'PUSH','{{name}}，您购物车里的家电还在，满 500 元可减 80 元。','name','ACTIVE',@actor,@t3h)
ON DUPLICATE KEY UPDATE channel_name=VALUES(channel_name),content_text=VALUES(content_text),required_variables=VALUES(required_variables),
  state_name=VALUES(state_name),created_by=VALUES(created_by),created_at=VALUES(created_at);
INSERT INTO marketing_engagement.mk_contact_attempt
  (tenant_id,contact_id,contact_key,subject_token,campaign_id,channel_name,template_id,template_version,state_name,
   variables_json,provider_request_id,provider_code,requested_at,updated_at)
VALUES (@tenant,'mock-contact-push-001','mock-cmd-push-001',@subject,@campaign,'PUSH','mock-tpl-cart-push',1,'DELIVERED',
  JSON_OBJECT('name','PLUS 会员','applicationId',@application),'mock-provider-request-001','MOCK_DELIVERED',@t1h,@now)
ON DUPLICATE KEY UPDATE state_name=VALUES(state_name),variables_json=VALUES(variables_json),provider_request_id=VALUES(provider_request_id),
  provider_code=VALUES(provider_code),requested_at=VALUES(requested_at),updated_at=VALUES(updated_at);
INSERT INTO marketing_engagement.mk_engagement_command
  (tenant_id,command_id,effect_type,enrollment_id,payload_hash,payload_json,state_name,contact_id,provider_code,last_error,created_at,updated_at)
VALUES (@tenant,'mock-cmd-push-001','SEND','mock-enr-cart-001',REPEAT('0',64),
  JSON_OBJECT('campaignId',@campaign,'channel','PUSH','templateId','mock-tpl-cart-push','subjectToken',@subject),
  'SUCCEEDED','mock-contact-push-001','MOCK_DELIVERED','',@t1h,@now)
ON DUPLICATE KEY UPDATE payload_hash=VALUES(payload_hash),payload_json=VALUES(payload_json),state_name=VALUES(state_name),
  contact_id=VALUES(contact_id),provider_code=VALUES(provider_code),last_error=VALUES(last_error),updated_at=VALUES(updated_at);
INSERT INTO marketing_engagement.mk_provider_receipt
  (tenant_id,provider_event_id,provider_request_id,status_name,occurred_at,attributes_json)
VALUES (@tenant,'mock-provider-event-001','mock-provider-request-001','DELIVERED',@now,JSON_OBJECT('channel','PUSH','campaignId',@campaign))
ON DUPLICATE KEY UPDATE provider_request_id=VALUES(provider_request_id),status_name=VALUES(status_name),occurred_at=VALUES(occurred_at),attributes_json=VALUES(attributes_json);
INSERT INTO marketing_engagement.mk_engagement_outbox_position
  (tenant_id,contact_id,last_sequence,updated_at)
VALUES (@tenant,'mock-contact-push-001',1,@now)
ON DUPLICATE KEY UPDATE last_sequence=GREATEST(last_sequence,VALUES(last_sequence)),updated_at=VALUES(updated_at);
INSERT INTO marketing_engagement.mk_engagement_outbox
  (tenant_id,event_id,contact_id,event_type,destination_topic,partition_key,stream_sequence,payload_json,
   publish_attempts,next_attempt_at,last_error,created_at,published_at,dead_lettered_at)
VALUES (@tenant,'mock-engagement-delivered','mock-contact-push-001','ContactDelivered','mk.engagement.event.v1',
  CONCAT(@tenant,':mock-contact-push-001'),1,JSON_OBJECT('eventId','mock-engagement-delivered','eventType','ContactDelivered','tenantId',@tenant,'contactId','mock-contact-push-001','campaignId',@campaign),
  0,@now,'',@now,@now,NULL)
ON DUPLICATE KEY UPDATE event_type=VALUES(event_type),payload_json=VALUES(payload_json),published_at=VALUES(published_at),created_at=VALUES(created_at);

-- 6. Measurement: experiment + funnel facts/projections + attribution + decision trace.
SET @experiment_json = JSON_OBJECT('experimentId',@experiment,'version','1','layer','checkout-offer','salt','mock-experiment-salt-2026',
  'variants',JSON_ARRAY(JSON_OBJECT('variantId','CONTROL','basisPoints',5000,'holdout',true),JSON_OBJECT('variantId','VARIANT_A','basisPoints',5000,'holdout',false)));
INSERT INTO marketing_measurement.mk_experiment
  (tenant_id,experiment_id,version_no,layer_name,definition_json,state_name,created_at)
VALUES (@tenant,@experiment,'1','checkout-offer',@experiment_json,'ACTIVE',@t6h)
ON DUPLICATE KEY UPDATE layer_name=VALUES(layer_name),definition_json=VALUES(definition_json),state_name=VALUES(state_name),created_at=VALUES(created_at);
INSERT INTO marketing_measurement.mk_experiment_assignment
  (tenant_id,experiment_id,version_no,layer_name,randomization_unit,variant_id,holdout_value,bucket_no,assigned_at)
VALUES (@tenant,@experiment,'1','checkout-offer',@subject,'VARIANT_A',false,7312,@t5h)
ON DUPLICATE KEY UPDATE variant_id=VALUES(variant_id),holdout_value=VALUES(holdout_value),bucket_no=VALUES(bucket_no),assigned_at=VALUES(assigned_at);
INSERT INTO marketing_measurement.mk_experiment_layer_assignment
  (tenant_id,layer_name,randomization_unit,experiment_id,assigned_at)
VALUES (@tenant,'checkout-offer',@subject,@experiment,@t5h)
ON DUPLICATE KEY UPDATE experiment_id=VALUES(experiment_id),assigned_at=VALUES(assigned_at);

INSERT INTO marketing_measurement.mk_fact
  (tenant_id,event_id,fact_type,business_key,subject_hash,occurred_at,ingested_at,schema_version,payload_hash,
   attributes_json,correction_of,correction_root_id,corrected_value,revenue_minor,cost_minor,experiment_id,experiment_version,variant_id)
VALUES
  (@tenant,'mock-decision-fact-001','DECISION','mock-decision-ha-001',@subject_hash,@t5h,@t5h,'1.0.0',REPEAT('1',64),JSON_OBJECT('campaignId',@campaign),'','mock-decision-fact-001',false,0,0,@experiment,'1','VARIANT_A'),
  (@tenant,'mock-offer-shown-001','OFFER_SHOWN',@order_id,@subject_hash,@t4h,@t4h,'1.0.0',REPEAT('2',64),JSON_OBJECT('campaignId',@campaign),'','mock-offer-shown-001',false,0,0,@experiment,'1','VARIANT_A'),
  (@tenant,'mock-promotion-applied-001','PROMOTION_APPLIED',@application,@subject_hash,@t3h,@t3h,'1.0.0',REPEAT('3',64),JSON_OBJECT('campaignId',@campaign,'applicationId',@application),'','mock-promotion-applied-001',false,0,8000,@experiment,'1','VARIANT_A'),
  (@tenant,'mock-contact-delivered-001','CONTACT_DELIVERED','mock-contact-push-001',@subject_hash,@t2h,@t2h,'1.0.0',REPEAT('4',64),JSON_OBJECT('campaignId',@campaign),'','mock-contact-delivered-001',false,0,0,@experiment,'1','VARIANT_A'),
  (@tenant,'mock-click-001','CLICK','mock-contact-push-001',@subject_hash,@t1h,@t1h,'1.0.0',REPEAT('5',64),JSON_OBJECT('campaignId',@campaign),'','mock-click-001',false,0,0,@experiment,'1','VARIANT_A'),
  (@tenant,'mock-conversion-ha-001','CONVERSION',@order_id,@subject_hash,@now,@now,'1.0.0',REPEAT('6',64),JSON_OBJECT('campaignId',@campaign,'revenueMinor','579900','costMinor','8000','experimentId',@experiment,'experimentVersion','1','variantId','VARIANT_A'),'','mock-conversion-ha-001',false,579900,8000,@experiment,'1','VARIANT_A')
ON DUPLICATE KEY UPDATE fact_type=VALUES(fact_type),business_key=VALUES(business_key),subject_hash=VALUES(subject_hash),occurred_at=VALUES(occurred_at),
  ingested_at=VALUES(ingested_at),schema_version=VALUES(schema_version),payload_hash=VALUES(payload_hash),attributes_json=VALUES(attributes_json),
  correction_of=VALUES(correction_of),correction_root_id=VALUES(correction_root_id),corrected_value=VALUES(corrected_value),
  revenue_minor=VALUES(revenue_minor),cost_minor=VALUES(cost_minor),experiment_id=VALUES(experiment_id),experiment_version=VALUES(experiment_version),variant_id=VALUES(variant_id);

INSERT INTO marketing_measurement.mk_dashboard_projection_delta
  (tenant_id,delta_id,root_event_id,source_event_id,revision_no,operation_name,fact_type,business_key,campaign_id,
   experiment_id,variant_id,subject_hash,count_delta,revenue_delta_minor,cost_delta_minor,occurred_at,ingested_at,
   payload_hash,source_topic,source_partition,source_offset,projected_at)
VALUES
  (@tenant,'mock-delta-decision','mock-decision-fact-001','mock-decision-fact-001',1,'UPSERT','DECISION','mock-decision-ha-001',@campaign,@experiment,'VARIANT_A',@subject_hash,1,0,0,@t5h,@t5h,REPEAT('7',64),CONCAT('mock.seed.',@tenant),0,1,@now),
  (@tenant,'mock-delta-shown','mock-offer-shown-001','mock-offer-shown-001',1,'UPSERT','OFFER_SHOWN',@order_id,@campaign,@experiment,'VARIANT_A',@subject_hash,1,0,0,@t4h,@t4h,REPEAT('8',64),CONCAT('mock.seed.',@tenant),0,2,@now),
  (@tenant,'mock-delta-applied','mock-promotion-applied-001','mock-promotion-applied-001',1,'UPSERT','PROMOTION_APPLIED',@application,@campaign,@experiment,'VARIANT_A',@subject_hash,1,0,8000,@t3h,@t3h,REPEAT('9',64),CONCAT('mock.seed.',@tenant),0,3,@now),
  (@tenant,'mock-delta-delivered','mock-contact-delivered-001','mock-contact-delivered-001',1,'UPSERT','CONTACT_DELIVERED','mock-contact-push-001',@campaign,@experiment,'VARIANT_A',@subject_hash,1,0,0,@t2h,@t2h,REPEAT('a',64),CONCAT('mock.seed.',@tenant),0,4,@now),
  (@tenant,'mock-delta-click','mock-click-001','mock-click-001',1,'UPSERT','CLICK','mock-contact-push-001',@campaign,@experiment,'VARIANT_A',@subject_hash,1,0,0,@t1h,@t1h,REPEAT('b',64),CONCAT('mock.seed.',@tenant),0,5,@now),
  (@tenant,'mock-delta-conversion','mock-conversion-ha-001','mock-conversion-ha-001',1,'UPSERT','CONVERSION',@order_id,@campaign,@experiment,'VARIANT_A',@subject_hash,1,579900,8000,@now,@now,REPEAT('c',64),CONCAT('mock.seed.',@tenant),0,6,@now)
ON DUPLICATE KEY UPDATE fact_type=VALUES(fact_type),business_key=VALUES(business_key),campaign_id=VALUES(campaign_id),
  experiment_id=VALUES(experiment_id),variant_id=VALUES(variant_id),subject_hash=VALUES(subject_hash),count_delta=VALUES(count_delta),
  revenue_delta_minor=VALUES(revenue_delta_minor),cost_delta_minor=VALUES(cost_delta_minor),occurred_at=VALUES(occurred_at),
  ingested_at=VALUES(ingested_at),payload_hash=VALUES(payload_hash),projected_at=VALUES(projected_at);

INSERT INTO marketing_measurement.mk_projection_watermark_config
  (tenant_id,projection_name,partition_count,updated_at)
VALUES (@tenant,'dashboard',1,@now)
ON DUPLICATE KEY UPDATE partition_count=VALUES(partition_count),updated_at=VALUES(updated_at);
INSERT INTO marketing_measurement.mk_projection_partition_watermark
  (tenant_id,projection_name,partition_id,source_offset,complete_through_epoch_ms,updated_at)
VALUES (@tenant,'dashboard',0,6,CAST(UNIX_TIMESTAMP(UTC_TIMESTAMP(3))*1000 AS UNSIGNED),@now)
ON DUPLICATE KEY UPDATE source_offset=VALUES(source_offset),complete_through_epoch_ms=VALUES(complete_through_epoch_ms),updated_at=VALUES(updated_at);

INSERT INTO marketing_measurement.mk_attribution_credit
  (tenant_id,conversion_event_id,policy_name,touch_event_id,credit_value,revenue_minor,calculated_at)
VALUES
  (@tenant,'mock-conversion-ha-001','LAST_TOUCH','mock-click-001',1.00000000,579900,@now),
  (@tenant,'mock-conversion-ha-001','LINEAR','mock-offer-shown-001',0.50000000,289950,@now),
  (@tenant,'mock-conversion-ha-001','LINEAR','mock-click-001',0.50000000,289950,@now)
ON DUPLICATE KEY UPDATE credit_value=VALUES(credit_value),revenue_minor=VALUES(revenue_minor),calculated_at=VALUES(calculated_at);

INSERT INTO marketing_measurement.mk_decision_trace
  (tenant_id,trace_id,request_id,order_id,subject_hash,generation_no,duration_micros,candidates_json,pricing_json,
   terms_version,expires_at,legal_hold,created_at)
VALUES (@tenant,'mock-trace-ha-001','mock-decision-ha-001',@order_id,@subject_hash,1842,823,
  JSON_OBJECT('mock-fixed-ha-80','APPLIED','requiredAudience',@audience,'audienceSnapshotId',@snapshot),
  JSON_OBJECT('currency','CNY','subtotalMinor',659900,'discountMinor',8000,'payableMinor',651900,'applicationId',@application),
  'mock-terms-offer-001',@next_month,false,@now)
ON DUPLICATE KEY UPDATE order_id=VALUES(order_id),subject_hash=VALUES(subject_hash),generation_no=VALUES(generation_no),
  duration_micros=VALUES(duration_micros),candidates_json=VALUES(candidates_json),pricing_json=VALUES(pricing_json),
  terms_version=VALUES(terms_version),expires_at=VALUES(expires_at),legal_hold=VALUES(legal_hold),created_at=VALUES(created_at);

COMMIT;

-- Human- and script-readable summary.
SELECT 'campaigns' AS dataset, COUNT(*) AS row_count FROM marketing_control.mk_campaign WHERE tenant_id=@tenant AND campaign_id LIKE 'mock-%'
UNION ALL SELECT 'definitions', COUNT(*) FROM marketing_control.mk_definition_version WHERE tenant_id=@tenant AND definition_id LIKE 'mock-%'
UNION ALL SELECT 'active_releases', COUNT(*) FROM marketing_control.mk_release_manifest WHERE tenant_id=@tenant AND manifest_id LIKE 'mock-%' AND state_name='ACTIVE'
UNION ALL SELECT 'audience_members', COUNT(*) FROM marketing_audience.mk_audience_member WHERE tenant_id=@tenant AND snapshot_id=@snapshot AND active_value=true
UNION ALL SELECT 'benefit_accounts', COUNT(*) FROM marketing_benefit.mk_resource_account WHERE tenant_id=@tenant AND resource_key IN (@inventory,@platform_budget,@merchant_budget)
UNION ALL SELECT 'promotion_applications', COUNT(*) FROM marketing_benefit.mk_promotion_application WHERE tenant_id=@tenant AND application_id=@application AND state_name='CONFIRMED'
UNION ALL SELECT 'journey_enrollments', COUNT(*) FROM marketing_journey.mk_enrollment WHERE tenant_id=@tenant AND enrollment_id='mock-enr-cart-001' AND status_name='COMPLETED'
UNION ALL SELECT 'delivered_contacts', COUNT(*) FROM marketing_engagement.mk_contact_attempt WHERE tenant_id=@tenant AND contact_id='mock-contact-push-001' AND state_name='DELIVERED'
UNION ALL SELECT 'measurement_facts', COUNT(*) FROM marketing_measurement.mk_fact WHERE tenant_id=@tenant AND event_id LIKE 'mock-%';
