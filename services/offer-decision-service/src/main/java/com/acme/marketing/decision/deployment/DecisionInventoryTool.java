package com.acme.marketing.decision.deployment;

import com.acme.marketing.contracts.release.DecisionDeploymentInventory;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;

/** 供 Compose/Kubernetes 流水线离线调用，仅签发或核验声明，不改变流量和运行状态。 */
public final class DecisionInventoryTool {
    private static final int MAX_KEY_BYTES = 4096;
    private static final String TRUST = "DECISION_DEPLOYMENT_TRUSTED_PUBLIC_KEYS";
    private DecisionInventoryTool() { }

    /** 私钥只经文件传入，stdout 专供签名清单或验证后的内容摘要供下游读取。 */
    public static void main(String[] args) throws Exception { run(args, System.out); }

    static void run(String[] args, java.io.PrintStream output) throws Exception {
        if (args.length == 4 && "sign".equals(args[0])) {
            var draft = read(Path.of(args[1]));
            var key = boundedRead(Path.of(args[2]), MAX_KEY_BYTES);
            try {
                var signer = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(key));
                var inventory = draft.sign(signer);
                Instant now = Instant.now();
                if (now.isBefore(inventory.observedAt()) || !now.isBefore(inventory.expiresAt()))
                    throw new IllegalArgumentException("只能签发当前有效的部署清单");
                output.println(DecisionInventoryJson.write(inventory));
                Files.writeString(Path.of(args[3]), inventory.contentDigest() + "\n",
                        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            } finally { Arrays.fill(key, (byte) 0); }
        } else if (args.length == 7 && "verify".equals(args[0])) {
            var inventory = read(Path.of(args[1]));
            inventory.requireCurrent(TrustedPublicKeys.parse("", "", System.getenv(TRUST)), args[2], args[3], args[4],
                    Long.parseLong(args[5]), args[6], Instant.now());
            output.println(inventory.contentDigest());
        } else {
            throw new IllegalArgumentException("用法：sign 草稿文件 PKCS8私钥文件 新摘要文件 或 verify 签名文件 环境 cell namespace 当前序号 当前摘要");
        }
        if (output.checkError()) throw new IOException("部署清单输出未完整写入");
    }

    static DecisionDeploymentInventory read(Path path) throws IOException {
        return DecisionInventoryJson.read(boundedRead(path, DecisionInventoryJson.MAX_DOCUMENT_BYTES));
    }

    private static byte[] boundedRead(Path path, int maximum) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("部署清单或密钥文件超过允许大小");
            return bytes;
        }
    }
}
