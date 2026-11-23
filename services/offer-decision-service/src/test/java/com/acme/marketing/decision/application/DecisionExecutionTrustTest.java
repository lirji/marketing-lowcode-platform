package com.acme.marketing.decision.application;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.release.DecisionDeploymentInventory;
import com.acme.marketing.platform.crypto.Ed25519;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DecisionExecutionTrustTest {
    @Test void fullServingInventoryCanPinOneDistinctExecutionKeyPerReplica() {
        String keys = IntStream.range(0, DecisionDeploymentInventory.MAX_REPLICAS)
                .mapToObj(index -> "instance-" + index + "=" + Base64.getEncoder()
                        .encodeToString(Ed25519.generateKeyPair().getPublic().getEncoded()))
                .collect(Collectors.joining(","));
        assertEquals(DecisionDeploymentInventory.MAX_REPLICAS, DecisionExecutionTrust.replicaTrust(keys).size());
        assertThrows(RuntimeException.class, () -> DecisionExecutionTrust.replicaTrust(keys + "," + keys.split(",")[0]));
    }
}
