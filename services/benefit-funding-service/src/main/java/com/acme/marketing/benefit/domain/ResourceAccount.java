package com.acme.marketing.benefit.domain;

import com.acme.marketing.platform.error.ConflictException;

public record ResourceAccount(
        String resourceKey,
        Type type,
        String currency,
        long authorized,
        long available,
        long reserved,
        long consumed,
        long returned,
        long fencingEpoch,
        long version,
        State state) {
    public ResourceAccount {
        if (resourceKey == null || type == null || currency == null || authorized < 0 || available < 0
                || reserved < 0 || consumed < 0 || returned < 0 || fencingEpoch < 1 || version < 0 || state == null) {
            throw new IllegalArgumentException("resource account is invalid");
        }
        assertInvariant(authorized, available, reserved, consumed);
    }

    public ResourceAccount reserve(long amount, long expectedEpoch) {
        writable(expectedEpoch);
        if (amount <= 0 || available < amount) {
            throw new ConflictException("REPRICE_REQUIRED", "resource capacity is insufficient: " + resourceKey);
        }
        return copy(available - amount, reserved + amount, consumed, returned);
    }

    public ResourceAccount confirm(long amount, long expectedEpoch) {
        writable(expectedEpoch);
        if (amount <= 0 || reserved < amount) throw new ConflictException("RESERVATION_INVALID", resourceKey);
        return copy(available, reserved - amount, consumed + amount, returned);
    }

    public ResourceAccount release(long amount, long expectedEpoch) {
        writable(expectedEpoch);
        if (amount <= 0 || reserved < amount) throw new ConflictException("RESERVATION_INVALID", resourceKey);
        return copy(available + amount, reserved - amount, consumed, returned);
    }

    public ResourceAccount refund(long amount, long expectedEpoch) {
        writable(expectedEpoch);
        if (amount <= 0 || consumed < amount) throw new ConflictException("REFUND_INVALID", resourceKey);
        return copy(available + amount, reserved, consumed - amount, Math.addExact(returned, amount));
    }

    public ResourceAccount grant(long amount, long expectedEpoch) {
        writable(expectedEpoch);
        if (amount <= 0 || available < amount) {
            throw new ConflictException("BENEFIT_UNAVAILABLE", "grant capacity is insufficient: " + resourceKey);
        }
        return copy(available - amount, reserved, consumed + amount, returned);
    }

    private ResourceAccount copy(long nextAvailable, long nextReserved, long nextConsumed, long nextReturned) {
        return new ResourceAccount(resourceKey, type, currency, authorized, nextAvailable, nextReserved,
                nextConsumed, nextReturned, fencingEpoch, version + 1, state);
    }

    private void writable(long expectedEpoch) {
        if (state != State.ACTIVE) throw new ConflictException("RESOURCE_FROZEN", resourceKey);
        if (expectedEpoch != fencingEpoch) {
            throw new ConflictException("FENCING_EPOCH_MISMATCH", resourceKey);
        }
    }

    private static void assertInvariant(long authorized, long available, long reserved, long consumed) {
        if (Math.addExact(Math.addExact(available, reserved), consumed) != authorized) {
            throw new IllegalArgumentException("resource conservation invariant violated");
        }
    }

    public enum Type { BUDGET, INVENTORY, PRIZE }
    public enum State { ACTIVE, FROZEN, CLOSED }
}
