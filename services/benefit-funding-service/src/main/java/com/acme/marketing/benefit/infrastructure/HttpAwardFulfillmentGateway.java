package com.acme.marketing.benefit.infrastructure;

import static com.acme.marketing.benefit.application.AwardFulfillmentErrors.*;

import com.acme.marketing.benefit.application.AwardFulfillmentGateway;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 通过正式只读合同查询权益订单；不把目录/POST 的委派权限当成 GET 的租户权限。 */
@Component
public final class HttpAwardFulfillmentGateway implements AwardFulfillmentGateway {
    private static final int MAX_RESPONSE_BYTES = 131_072;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final OpenTelemetry telemetry;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final Semaphore permits = new Semaphore(4);

    /** HTTP 地址和凭据来自受控配置，业务请求不能指定上游主机或身份。 */
    public HttpAwardFulfillmentGateway(ObjectMapper mapper, OpenTelemetry telemetry,
            @Value("${marketing.benefit-center.base-url:http://127.0.0.1:8183}") String baseUrl,
            @Value("${marketing.benefit-center.fulfillment-bearer-token:}") String token,
            @Value("${marketing.benefit-center.connect-timeout-ms:2000}") long connectMs,
            @Value("${marketing.benefit-center.request-timeout-ms:3000}") long timeoutMs) {
        if (connectMs < 100 || connectMs > 30_000 || timeoutMs < 100 || timeoutMs > 30_000) throw new IllegalArgumentException("履约查询超时配置无效");
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) throw new IllegalArgumentException("权益中心地址无效");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs)).build();
        this.mapper = mapper; this.telemetry = telemetry; this.token = token == null ? "" : token.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override public Order query(String tenantId, String sourceSystem, String sourceRequestId) {
        if (!permits.tryAcquire()) throw new Unavailable(AWARD_CENTER_QUERY_CAPACITY);
        var span = telemetry.getTracer("marketing-award-observer").spanBuilder("benefit.order.lookup").setSpanKind(SpanKind.CLIENT).startSpan();
        var scope = span.makeCurrent();
        try {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + "/openapi/v1/award-orders?sourceSystem="
                    + encode(sourceSystem) + "&sourceRequestId=" + encode(sourceRequestId)))
                    .timeout(timeout).header("Accept", "application/json").header("X-Tenant-Id", tenantId).GET();
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            telemetry.getPropagators().getTextMapPropagator().inject(Context.current(), builder, HttpRequest.Builder::header);
            // 覆盖响应正文读取的总时限，并在流式接收时限制体积，而不是读完后才检查。
            var request = http.sendAsync(builder.build(), info -> new LimitedBody());
            HttpResponse<String> response;
            try {
                response = request.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                if (!request.isDone()) request.cancel(true);
            }
            if (response.statusCode() == 404) throw new Unavailable(AWARD_CENTER_ORDER_NOT_FOUND);
            if (response.statusCode() != 200) throw new Unavailable(AWARD_CENTER_QUERY_HTTP_ + response.statusCode());
            return mapper.readValue(response.body(), Order.class);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); span.setStatus(StatusCode.ERROR);
            throw new Unavailable(AWARD_CENTER_QUERY_INTERRUPTED);
        } catch (Unavailable unavailable) {
            span.setStatus(StatusCode.ERROR); throw unavailable;
        } catch (TimeoutException timeoutFailure) {
            span.setStatus(StatusCode.ERROR); throw new Unavailable(AWARD_CENTER_QUERY_TIMEOUT);
        } catch (ExecutionException invalid) {
            span.setStatus(StatusCode.ERROR);
            if (invalid.getCause() instanceof Unavailable unavailable) throw unavailable;
            throw new Unavailable(AWARD_CENTER_QUERY_UNAVAILABLE);
        } catch (RuntimeException invalid) {
            span.setStatus(StatusCode.ERROR); throw new Unavailable(AWARD_CENTER_QUERY_UNAVAILABLE);
        } finally { scope.close(); span.end(); permits.release(); }
    }

    /** 关闭时终止未结束的网络流，任务仍由数据库租约恢复。 */
    @PreDestroy
    public void close() { http.shutdownNow(); }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    /** 对成功和错误响应都设置硬上限，避免下游异常正文耗尽内存。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private long received;
        private boolean rejected;

        @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (rejected) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > MAX_RESPONSE_BYTES) {
                rejected = true; subscription.cancel();
                delegate.onError(new Unavailable(AWARD_CENTER_QUERY_INVALID));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure) { if (!rejected) delegate.onError(failure); }
        @Override public void onComplete() { if (!rejected) delegate.onComplete(); }
    }
}
