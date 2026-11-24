package com.acme.marketing.audience.infrastructure;

import static com.acme.marketing.audience.application.CommerceProfileError.SOURCE_DISABLED;
import static com.acme.marketing.audience.application.CommerceProfileError.SOURCE_LIMIT;
import static com.acme.marketing.audience.application.CommerceProfileError.SOURCE_UNAVAILABLE;

import com.acme.marketing.audience.application.CommerceProfileSource;
import com.acme.marketing.platform.identity.TenantId;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 商城内部画像合同适配器。认证凭据、超时、响应体和并发预算只作用于该来源。 */
@Component
public final class CommerceMemberProfileClient implements CommerceProfileSource {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final String PATH = "/internal/v1/member-profiles/snapshot";
    private static final String LEVEL_SUMMARY_PATH = PATH + "?view=level-summary";
    private final Semaphore reads = new Semaphore(2);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final ObjectMapper mapper;
    private final String marketingTenant;
    private final HttpRequest snapshotRequest;
    private final HttpRequest estimateRequest;

    public CommerceMemberProfileClient(ObjectMapper mapper,
            @Value("${marketing.audience.commerce.enabled:false}") boolean enabled,
            @Value("${marketing.audience.commerce.tenant:}") String tenant,
            @Value("${marketing.audience.commerce.base-url:}") String baseUrl,
            @Value("${marketing.audience.commerce.bearer-token:}") String bearerToken,
            @Value("${marketing.audience.commerce.allow-local-http:false}") boolean allowLocalHttp,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        this.mapper = mapper;
        if (!enabled) {
            this.snapshotRequest = null;
            this.estimateRequest = null;
            this.marketingTenant = null;
            return;
        }
        this.marketingTenant = new TenantId(tenant).value();
        URI base = URI.create(baseUrl);
        boolean permitsHttp = allowLocalHttp && "DEV".equals(securityMode) && "http".equals(base.getScheme());
        if ((!permitsHttp && !"https".equals(base.getScheme())) || base.getHost() == null || base.getUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null
                || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                || !bearerToken.matches("[A-Za-z0-9_-]{43,128}")) {
            throw new IllegalArgumentException("商城画像来源缺少合法地址或独立机器凭据");
        }
        this.snapshotRequest = request(base.resolve(PATH), bearerToken);
        this.estimateRequest = request(base.resolve(LEVEL_SUMMARY_PATH), bearerToken);
    }

    private HttpRequest request(URI address, String bearerToken) {
        return HttpRequest.newBuilder(address)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .header("X-Marketing-Tenant", marketingTenant)
                .timeout(TIMEOUT).GET().build();
    }

    /** 仅已配对的营销租户可发现和访问当前来源。 */
    @Override
    public boolean supports(String tenant) {
        return tenant.equals(marketingTenant);
    }

    /** 整次接收有截止时间和字节上限，拒绝截断、错误鉴权和超时响应。 */
    @Override
    public SourceSnapshot read(String tenant) {
        return read(tenant, snapshotRequest, bytes -> mapper.readValue(bytes, SourceSnapshot.class));
    }

    /** 同一请求支持新旧响应，避免额外回源或放宽整次读取预算。 */
    @Override
    public EstimateObservation readEstimate(String tenant) {
        return read(tenant, estimateRequest, bytes -> {
            var body = mapper.readTree(bytes);
            if (body == null || !body.isObject() || !body.path("asOf").isString()
                    || body.has("levels") == body.has("profiles")) {
                throw SOURCE_UNAVAILABLE.unavailable();
            }
            if (body.has("profiles")) return mapper.treeToValue(body, SourceSnapshot.class);
            var levels = body.path("levels");
            if (!levels.isArray()) throw SOURCE_UNAVAILABLE.unavailable();
            // Jackson 可将浮点数或字符串转为长整数，汇总拒绝这类隐式转换。
            for (var level : levels) {
                if (!level.path("memberLevel").isString() || !level.path("memberCount").isIntegralNumber()) {
                    throw SOURCE_UNAVAILABLE.unavailable();
                }
            }
            return mapper.treeToValue(body, LevelSummary.class);
        });
    }

    private <T> T read(String tenant, HttpRequest request, Function<byte[], T> decode) {
        if (!supports(tenant)) {
            throw SOURCE_DISABLED.conflict();
        }
        if (!reads.tryAcquire()) throw SOURCE_UNAVAILABLE.unavailable();
        try {
            var bytes = new ByteArrayOutputStream();
            var call = http.sendAsync(request, info -> info.statusCode() == HttpStatus.OK.value()
                    ? HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(part -> {
                        if (part.length > MAX_BYTES - bytes.size()) throw SOURCE_UNAVAILABLE.unavailable();
                        bytes.writeBytes(part);
                    })) : HttpResponse.BodySubscribers.discarding());
            try {
                int status = call.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).statusCode();
                if (status == HttpStatus.CONFLICT.value()) {
                    throw SOURCE_LIMIT.conflict();
                }
                if (status != HttpStatus.OK.value()) throw SOURCE_UNAVAILABLE.unavailable();
                return decode.apply(bytes.toByteArray());
            } catch (InterruptedException interrupted) {
                call.cancel(true);
                Thread.currentThread().interrupt();
                throw SOURCE_UNAVAILABLE.unavailable();
            } catch (TimeoutException | ExecutionException | JacksonException failed) {
                call.cancel(true);
                throw SOURCE_UNAVAILABLE.unavailable();
            }
        } finally {
            reads.release();
        }
    }

}
