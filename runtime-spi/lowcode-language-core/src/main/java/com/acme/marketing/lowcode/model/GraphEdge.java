package com.acme.marketing.lowcode.model;

public record GraphEdge(String id, String sourceNodeId, String sourcePort, String targetNodeId, String targetPort) {
    public GraphEdge {
        id = require(id, "id");
        sourceNodeId = require(sourceNodeId, "sourceNodeId");
        sourcePort = require(sourcePort, "sourcePort");
        targetNodeId = require(targetNodeId, "targetNodeId");
        targetPort = require(targetPort, "targetPort");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
