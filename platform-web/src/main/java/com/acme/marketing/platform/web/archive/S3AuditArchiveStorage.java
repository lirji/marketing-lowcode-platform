package com.acme.marketing.platform.web.archive;

import com.acme.marketing.platform.error.DependencyUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 固定营销来源前缀和桶，通过分离的写入、回读角色保全精确对象版本。 */
public final class S3AuditArchiveStorage implements AuditArchiveStorage, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(S3AuditArchiveStorage.class);
    private static final int MAX_BYTES = 512 * 1024;
    private static final String UUID_KEY = "[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}";
    private static final String UNAVAILABLE = "营销审计档案存储暂不可用";
    private final S3Client writer;
    private final S3Client reader;
    private final String bucket;
    private final String prefix;

    public S3AuditArchiveStorage(URI endpoint, String region, String bucket, String prefix,
            String writeKey, String writeSecret, String restoreKey, String restoreSecret) {
        if (endpoint.getHost() == null || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null || endpoint.getQuery() != null
                || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || !prefix.matches("marketing/[a-z-]+/operation-audit/v1/[A-Za-z0-9_-]{1,64}/")
                || writeKey.isBlank() || restoreKey.isBlank() || writeSecret.isBlank() || restoreSecret.isBlank()
                || writeKey.equals(restoreKey) || writeSecret.equals(restoreSecret)) {
            throw new IllegalArgumentException("营销审计档案需要固定来源桶及独立写入和回读身份");
        }
        this.bucket = bucket;
        this.prefix = prefix;
        this.writer = client(endpoint, region, writeKey, writeSecret);
        this.reader = client(endpoint, region, restoreKey, restoreSecret);
    }

    @Override
    public String putOnce(String key, byte[] bytes, String sha256) {
        requireKey(key);
        requireSize(bytes.length);
        try {
            if (writer.getBucketVersioning(request -> request.bucket(bucket)).status() != BucketVersioningStatus.ENABLED) throw unavailable();
            try {
                var added = writer.putObject(request -> request.bucket(bucket).key(key)
                        .contentType(key.endsWith(".json") ? "application/json" : "application/gzip")
                        .ifNoneMatch("*").metadata(Map.of("sha256", sha256)), RequestBody.fromBytes(bytes));
                return version(added.versionId());
            } catch (S3Exception failure) {
                if (failure.statusCode() != 412) throw failure;
                // 之前上传可能已提交，严格校验之前的元数据和版本，之后仍回读正文。
                var prior = writer.headObject(request -> request.bucket(bucket).key(key));
                if (!sha256.equals(prior.metadata().get("sha256"))) throw unavailable();
                return version(prior.versionId());
            }
        } catch (SdkException failure) {
            throw unavailable("put", failure);
        }
    }

    @Override
    public byte[] verify(String key, String objectVersion, int bytes) {
        return read(writer, key, objectVersion, bytes);
    }

    @Override
    public byte[] recover(String key, String objectVersion, int bytes) {
        return read(reader, key, objectVersion, bytes);
    }

    /** 清点严格限定签收来源和单一凭证前缀。 */
    @Override
    public Inventory inventory(String prefix, String continuation, int limit) {
        if (!prefix.equals(this.prefix + "recovery/prepared/") || limit < 1 || limit > 20) throw unavailable();
        try {
            var page = reader.listObjectsV2(request -> request.bucket(bucket).prefix(prefix).maxKeys(limit)
                    .continuationToken(continuation));
            if (Boolean.TRUE.equals(page.isTruncated()) && page.nextContinuationToken() == null) throw unavailable();
            return new Inventory(page.contents().stream().map(item -> item.key()).toList(), page.nextContinuationToken());
        } catch (SdkException failure) {
            throw unavailable("inventory", failure);
        }
    }

    /** 暴露当前提交凭证的版本信息，旧备份随后按这个版本回读而不是按可变对象键。 */
    @Override
    public ObjectReference current(String key) {
        requireKey(key);
        if (!key.startsWith(prefix + "recovery/committed/")) throw unavailable();
        try {
            var object = reader.headObject(request -> request.bucket(bucket).key(key));
            int bytes = Math.toIntExact(object.contentLength());
            requireSize(bytes);
            String digest = object.metadata().get("sha256");
            if (digest == null || !digest.matches("[0-9a-f]{64}")) throw unavailable();
            return new ObjectReference(version(object.versionId()), bytes, digest);
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) return null;
            throw unavailable("current", failure);
        } catch (SdkException failure) {
            throw unavailable("current", failure);
        }
    }

    private byte[] read(S3Client client, String key, String objectVersion, int bytes) {
        requireKey(key);
        requireSize(bytes);
        version(objectVersion);
        try (var stream = client.getObject(request -> request.bucket(bucket).key(key).versionId(objectVersion))) {
            if (!objectVersion.equals(stream.response().versionId()) || stream.response().contentLength() != bytes) throw unavailable();
            return stream.readNBytes(bytes + 1);
        } catch (IOException | SdkException failure) {
            throw unavailable("read", failure);
        }
    }

    private void requireKey(String key) {
        if (key == null || !key.startsWith(prefix)) throw unavailable();
        String relative = key.substring(prefix.length());
        if (!(relative.matches(UUID_KEY + "\\.ndjson\\.gz")
                || relative.matches("recovery/(prepared|committed)/" + UUID_KEY + "\\.json"))) throw unavailable();
    }

    private static void requireSize(int size) {
        if (size < 1 || size > MAX_BYTES) throw unavailable();
    }

    private static String version(String objectVersion) {
        if (objectVersion == null || objectVersion.isBlank() || "null".equals(objectVersion) || objectVersion.length() > 255) throw unavailable();
        return objectVersion;
    }

    private static S3Client client(URI endpoint, String region, String key, String secret) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(12))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5))).build();
    }

    private static DependencyUnavailableException unavailable(String operation, Exception failure) {
        Integer status = failure instanceof S3Exception response ? response.statusCode() : null;
        LOG.warn("营销审计档案存储未确认 operation={} status={} errorType={}", operation, status, failure.getClass().getSimpleName());
        return unavailable();
    }

    private static DependencyUnavailableException unavailable() {
        return new DependencyUnavailableException("AUDIT_ARCHIVE_UNAVAILABLE", UNAVAILABLE);
    }

    @Override
    public void close() {
        writer.close();
        reader.close();
    }
}
