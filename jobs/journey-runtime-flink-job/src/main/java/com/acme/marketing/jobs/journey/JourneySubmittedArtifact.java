package com.acme.marketing.jobs.journey;

import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarFile;

/** 在构造job graph前对实际加载的可执行JAR计算摘要，不接受请求或环境自报摘要。 */
final class JourneySubmittedArtifact {
    private static final long MAX_JAR_BYTES = 512L * 1024 * 1024;
    private static final int BUFFER_BYTES = 64 * 1024;
    private JourneySubmittedArtifact() { }
    static String currentDigest() {
        try {
            return digest(Path.of(JourneyRuntimeFlinkJob.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (java.net.URISyntaxException | IllegalArgumentException | SecurityException failure) { throw invalid(); }
    }
    /** 流式读取并验证入口；目录classpath和无入口的归档不能证明实际提交制品。 */
    static String digest(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) < 1 || Files.size(path) > MAX_JAR_BYTES) throw invalid();
            try (JarFile jar = new JarFile(path.toFile())) {
                if (jar.getManifest() == null || !JourneyRuntimeFlinkJob.class.getName().equals(
                        jar.getManifest().getMainAttributes().getValue("Main-Class"))) throw invalid();
            }
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[BUFFER_BYTES]; int read; long size = 0;
                while ((read = input.read(buffer)) != -1) {
                    size += read; if (size > MAX_JAR_BYTES) throw invalid(); digest.update(buffer, 0, read);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | SecurityException failure) { throw invalid(); }
    }
    private static IllegalStateException invalid() { return new IllegalStateException(ReleaseBundleProtocolError.EXECUTION_ARTIFACT_INVALID.message()); }
}
