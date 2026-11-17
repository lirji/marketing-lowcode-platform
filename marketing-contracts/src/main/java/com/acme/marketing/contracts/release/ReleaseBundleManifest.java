package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.identity.TenantId;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 完整活动组合的来源证明；此签名不授予激活流量的权限。 */
public record ReleaseBundleManifest(String bundleId, TenantId tenantId, String campaignId, long version,
        String environment, String cell, String contentHash, List<ReleaseManifest> members,
        Instant createdAt, Instant expiresAt, String signatureKeyId, String signature) {
    public static final int MAX_MEMBERS = 8;
    private static final String INVALID = "activity release manifest is invalid";

    public ReleaseBundleManifest {
        if (!identifier(bundleId) || tenantId == null || !identifier(campaignId) || version < 1
                || !bounded(environment, 32) || !identifier(cell) || !identifier(signatureKeyId)
                || createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)
                || contentHash == null || !contentHash.matches("[a-f0-9]{64}")
                || members == null || members.size() < 2 || members.size() > MAX_MEMBERS
                || members.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException(INVALID);
        members = members.stream().sorted(Comparator.comparing(ReleaseManifest::manifestId)).toList();
        var ids = new HashSet<String>();
        var slots = new HashSet<List<String>>();
        for (ReleaseManifest member : members) {
            if (!tenantId.equals(member.tenantId()) || !environment.equals(member.environment())
                    || !cell.equals(member.cell()) || member.canaryBasisPoints() != 0
                    || member.signature().isBlank() || member.createdAt().isAfter(createdAt)
                    || !ids.add(member.manifestId()) || !slots.add(List.of(member.runtime(), member.namespace())))
                throw new IllegalArgumentException(INVALID);
        }
        Instant earliestExpiry = members.stream().map(ReleaseManifest::expiresAt).min(Instant::compareTo).orElseThrow();
        if (!earliestExpiry.equals(expiresAt)
                || !contentHash.equals(contentHash(tenantId.value(), campaignId, members)))
            throw new IllegalArgumentException(INVALID);
        signature = signature == null ? "" : signature;
    }

    /** 与首版准备接口保持同一长度编码摘要，不因外层签名轮换改变业务版本。 */
    public static String contentHash(String tenant, String campaign, List<ReleaseManifest> members) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("tenant", tenant); values.put("campaign", campaign);
        List<ReleaseManifest> ordered = members.stream().sorted(Comparator.comparing(ReleaseManifest::manifestId)).toList();
        for (int i = 0; i < ordered.size(); i++) {
            values.put("member." + i + ".id", ordered.get(i).manifestId());
            values.put("member." + i + ".signature", ordered.get(i).signature());
        }
        return Digests.sha256Hex(CanonicalMapCodec.encode(values));
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,64}");
    }
    private static boolean bounded(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }
}
