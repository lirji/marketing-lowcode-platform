package com.acme.marketing.decision.runtime;

import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ReleaseManifest;
import java.util.List;
import java.util.Optional;

/** Shared desired-state store used by every decision replica. */
public interface RuntimeStateStore {
    void install(String releaseKeyId, ReleaseManifest manifest, byte[] artifactPayload);

    /** 查询真实应用后是否必须携带活动参与凭证。 */
    boolean requiresBinding(RuntimeManifestRegistry.RuntimeSlot slot, String tenantId);

    void activate(ActivationDirective directive);

    Optional<DesiredState> desired(RuntimeManifestRegistry.RuntimeSlot slot, String tenantId);

    List<DesiredPointer> desiredPointers(RuntimeManifestRegistry.RuntimeSlot slot);

    Optional<StoredGeneration> generation(RuntimeManifestRegistry.RuntimeSlot slot, String tenantId, long generation);

    record StoredGeneration(String releaseKeyId, ReleaseManifest manifest, byte[] artifactPayload) {
        public StoredGeneration {
            artifactPayload = artifactPayload.clone();
        }

        @Override
        public byte[] artifactPayload() {
            return artifactPayload.clone();
        }
    }

    record DesiredState(StoredGeneration generation, ActivationDirective directive) {
        public DesiredState {
            if (generation == null || directive == null) {
                throw new IllegalArgumentException("desired runtime state is incomplete");
            }
        }
    }

    record DesiredPointer(String tenantId, long generation, long activationSequence) { }
}
