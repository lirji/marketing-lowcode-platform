package com.acme.marketing.contracts.release;

public record ArtifactReference(
        String artifactId,
        String type,
        String uri,
        String checksum,
        String sourceDigest,
        String signatureKeyId,
        String signature,
        String abi,
        String definitionId,
        long definitionVersion) {
    public ArtifactReference {
        artifactId = require(artifactId, "artifactId");
        type = require(type, "type");
        uri = require(uri, "uri");
        if (checksum == null || !checksum.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("artifact checksum is invalid");
        }
        if (sourceDigest == null || !sourceDigest.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("artifact source digest is invalid");
        }
        signatureKeyId = require(signatureKeyId, "signatureKeyId");
        signature = require(signature, "signature");
        abi = require(abi, "abi");
        definitionId = require(definitionId, "definitionId");
        if (definitionVersion < 1) throw new IllegalArgumentException("definitionVersion is invalid");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
