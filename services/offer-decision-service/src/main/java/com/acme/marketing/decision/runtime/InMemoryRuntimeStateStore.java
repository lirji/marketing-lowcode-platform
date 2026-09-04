package com.acme.marketing.decision.runtime;

import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.platform.error.ConflictException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;

final class InMemoryRuntimeStateStore implements RuntimeStateStore {
    private final Map<String, Pointer> desired = new HashMap<>();
    private final Map<String, StoredGeneration> generations = new HashMap<>();

    @Override
    public synchronized void install(String releaseKeyId, ReleaseManifest manifest, byte[] artifactPayload) {
        String slotKey = manifest.slotKey();
        StoredGeneration current = generations.get(slotKey + ':' + manifest.generation());
        if (current != null && !manifest.signature().equals(current.manifest().signature())) {
            throw new ConflictException("MANIFEST_GENERATION_CONFLICT",
                    "generation already points at a different signed manifest");
        }
        StoredGeneration stored = new StoredGeneration(releaseKeyId, manifest, artifactPayload);
        generations.put(slotKey + ':' + manifest.generation(), stored);
    }

    @Override
    public synchronized void activate(ActivationDirective directive) {
        String slotKey = directive.slotKey();
        StoredGeneration generation = generations.get(slotKey + ':' + directive.generation());
        if (generation == null || !generation.manifest().signature().equals(directive.manifestSignature())) {
            throw new ConflictException("ACTIVATION_GENERATION_NOT_INSTALLED",
                    "activation references a generation that is not installed");
        }
        Pointer current = desired.get(slotKey);
        if (current != null && directive.activationSequence() < current.sequence()) {
            throw new ConflictException("ACTIVATION_SEQUENCE_STALE", "activation directive is stale");
        }
        if (current != null && directive.activationSequence() == current.sequence()) {
            if (!current.signature().equals(directive.signature())) {
                throw new ConflictException("ACTIVATION_SEQUENCE_CONFLICT", "activation sequence was reused");
            }
            return;
        }
        desired.put(slotKey, new Pointer(directive.tenantId().value(), directive));
    }

    @Override
    public synchronized Optional<DesiredState> desired(
            RuntimeManifestRegistry.RuntimeSlot slot, String tenantId) {
        Pointer pointer = desired.get(slot.key(tenantId));
        if (pointer == null) return Optional.empty();
        return generation(slot, tenantId, pointer.directive().generation())
                .map(stored -> new DesiredState(stored, pointer.directive()));
    }

    @Override
    public synchronized List<DesiredPointer> desiredPointers(RuntimeManifestRegistry.RuntimeSlot slot) {
        return desired.values().stream()
                .filter(pointer -> slot.matches(pointer.directive()))
                .map(pointer -> new DesiredPointer(pointer.tenantId(), pointer.directive().generation(),
                        pointer.directive().activationSequence()))
                .toList();
    }

    @Override
    public synchronized Optional<StoredGeneration> generation(
            RuntimeManifestRegistry.RuntimeSlot slot, String tenantId, long generation) {
        return Optional.ofNullable(generations.get(slot.key(tenantId) + ':' + generation));
    }

    private record Pointer(String tenantId, ActivationDirective directive) {
        private long sequence() { return directive.activationSequence(); }
        private String signature() { return directive.signature(); }
    }
}
