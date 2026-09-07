package com.acme.marketing.compiler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acme.marketing.compiler.application.ArtifactStore;
import com.acme.marketing.contracts.artifact.ArtifactBundle;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuleCompilerApplicationTest extends MySqlIntegrationTest {
    @Autowired private ArtifactStore artifactStore;

    @Test
    void mybatisStorePersistsAndValidatesContentAddressedArtifacts() {
        byte[] payload = "compiled-policy".getBytes(StandardCharsets.UTF_8);
        String checksum = "sha256:" + Digests.sha256Hex(payload);
        ArtifactBundle artifact = new ArtifactBundle("artifact-1", "tenant-a", "definition-1", 1,
                "OFFER", "abi-v1", payload, checksum, checksum, "key-1", "signature-1",
                Map.of("compiler", "drools"), Instant.parse("2026-09-07T01:00:00Z"));

        assertEquals(artifact.artifactId(), artifactStore.putIfAbsent(artifact).artifactId());
        ArtifactBundle stored = artifactStore.find("tenant-a", "artifact-1").orElseThrow();
        assertEquals(artifact.metadata(), stored.metadata());
        assertArrayEquals(payload, stored.payload());
        assertEquals(checksum, artifactStore.putIfAbsent(artifact).checksum());

        ArtifactBundle collision = new ArtifactBundle("artifact-1", "tenant-a", "definition-1", 1,
                "OFFER", "abi-v1", payload, checksum, checksum, "key-1", "different-signature",
                Map.of("compiler", "drools"), artifact.compiledAt());
        assertThrows(ConflictException.class, () -> artifactStore.putIfAbsent(collision));
    }
}
