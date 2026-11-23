package com.acme.marketing.control.infrastructure;

import com.acme.marketing.contracts.release.JourneyBundleExecutionAck;
import com.acme.marketing.contracts.release.JourneyBundleExecutionProbe;
import com.acme.marketing.contracts.release.JourneyBundleExecutionProbeSigner;
import com.acme.marketing.contracts.release.ReleaseBundleAck;
import com.acme.marketing.contracts.release.ReleaseBundleActivationDirective;
import com.acme.marketing.contracts.release.ReleaseBundleManifest;
import com.acme.marketing.contracts.release.ReleaseBundlePrepareRequest;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.control.application.BundleReleaseRuntime;
import com.acme.marketing.control.application.ReleaseBundleError;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 配置给出受信投递地址；副本覆盖仍由 Decision 当前已验签的部署名单判定。 */
@Component
public final class HttpBundleReleaseRuntime implements BundleReleaseRuntime {
    private static final String DECISION = "decision", JOURNEY = "journey";
    private static final String DECISION_ROOT = "/api/v1/decisions/runtime", JOURNEY_ROOT = "/api/v1/journey-runtime";
    private static final String DIRECTIVES = "/release-bundle-directives", PREPARE = "/release-bundles:prepare";
    private static final String PROBES = JOURNEY_ROOT + "/execution-probes";
    private static final String ABSENT = "RELEASE_BUNDLE_ADMISSION_NOT_APPLIED";
    private static final Duration PROBE_PERIOD = Duration.ofMinutes(5);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration PASS_TIMEOUT = Duration.ofSeconds(24);
    private static final int MAX_TARGETS = 128, MAX_REPLICAS = 64, MAX_REPLY = 2 * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final Clock clock;
    private final SigningKeyRing keys;
    private final Map<Location, Target> targets = new HashMap<>();
    private final boolean dev;
    private final BundleMachineCredential credentials;

    /** 默认空配置可启动；任何缺少实际地址、身份或作业部署参数的成员都拒绝发布。 */
    public HttpBundleReleaseRuntime(ObjectMapper json, Clock clock, SigningKeyRing keys,
            @Value("${marketing.bundle-runtime.targets:[]}") String configured,
            @Value("${marketing.bundle-runtime.bearer-token:}") String token,
            @Value("${marketing.security.mode:DEV}") String mode) {
        this.json = json; this.clock = clock; this.keys = keys; this.credentials = new BundleMachineCredential(json, token);
        this.dev = "DEV".equalsIgnoreCase(mode);
        Target[] values = configured.isBlank() ? new Target[0] : json.readValue(configured, Target[].class);
        if (values == null || values.length > MAX_TARGETS) throw ReleaseBundleError.RUNTIME_CONFIG.invalid();
        for (Target target : values) {
            if (target == null || target.urls() == null || target.urls().isEmpty() || target.urls().size() > MAX_REPLICAS
                    || target.environment() == null || target.cell() == null || target.namespace() == null
                    || (!DECISION.equals(target.runtime()) && !JOURNEY.equals(target.runtime()))
                    || target.urls().stream().distinct().count() != target.urls().size()
                    || (JOURNEY.equals(target.runtime()) && (target.jobId() == null || !target.jobId().matches("[a-f0-9]{32}")
                    || target.parallelism() == null || target.parallelism() < 1 || target.parallelism() > JourneyBundleExecutionProbe.MAX_PARALLELISM
                    || target.participationKeyId() == null || target.participationKeyId().isBlank())))
                throw ReleaseBundleError.RUNTIME_CONFIG.invalid();
            for (String url : target.urls()) {
                URI uri = URI.create(url);
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                        || !uri.getPath().isEmpty() || !("https".equals(uri.getScheme()) || (dev && token.isBlank() && "http".equals(uri.getScheme()))))
                    throw ReleaseBundleError.RUNTIME_CONFIG.invalid();
            }
            var location = new Location(target.environment(), target.cell(), target.runtime(), target.namespace());
            if (targets.putIfAbsent(location, target) != null) throw ReleaseBundleError.RUNTIME_CONFIG.invalid();
        }
    }

    @Override
    public List<ReleaseBundleAck> prepare(ReleaseBundleManifest bundle) {
        var acks = new ArrayList<ReleaseBundleAck>();
        var session = new Calls();
        for (var member : bundle.members()) {
            var target = target(bundle, member);
            for (String url : target.urls()) {
                var body = new ReleaseBundlePrepareRequest(bundle, member.manifestId());
                acks.add(read(session.post(url, target.root() + PREPARE, body), ReleaseBundleAck.class));
            }
        }
        return List.copyOf(acks);
    }

    @Override
    public Optional<Applied> findApplied(ReleaseBundleActivationDirective directive, Instant confirmAbsentAfter) {
        Target authority = authority(directive.bundle());
        String path = applicationPath(directive);
        if (confirmAbsentAfter != null) path += "?confirmAbsentAfter=" + confirmAbsentAfter.toString();
        JsonNode result = new Calls().request(authority.urls().getFirst(), path, false, null, true);
        return result == null ? Optional.empty() : Optional.of(read(result, Applied.class));
    }

    @Override
    public Applied apply(ReleaseBundleActivationDirective directive, long expectedSequence, Instant deadline,
            Runnable finalControlGate) {
        var session = new Calls();
        var bundle = directive.bundle();
        var authority = authority(bundle);
        var evidence = new ArrayList<JourneyBundleExecutionAck>();
        boolean admissionVisited = false;
        boolean allJobsReady = true;
        for (var member : bundle.members()) {
            var target = target(bundle, member);
            record(session, target, directive);
            if (DECISION.equals(target.runtime())) {
                attest(session, target, directive);
                admissionVisited |= target.equals(authority);
            } else {
                var receipts = executeJourney(session, target, directive, member, deadline);
                allJobsReady &= receipts.isPresent();
                receipts.ifPresent(evidence::addAll);
            }
        }
        if (!admissionVisited) {
            record(session, authority, directive);
            attest(session, authority, directive);
        }
        if (!allJobsReady) throw ReleaseBundleError.PROBE_PENDING.conflict();
        finalControlGate.run();
        if (!clock.instant().isBefore(deadline)) throw ReleaseBundleError.OPERATION_EXPIRED.conflict();
        return read(session.post(authority.urls().getFirst(), applicationPath(directive) + "/apply",
                new Apply(expectedSequence, deadline, List.copyOf(evidence))), Applied.class);
    }

    private void record(Calls session, Target target, ReleaseBundleActivationDirective directive) {
        var result = session.post(target.urls().getFirst(), target.root() + DIRECTIVES, directive);
        if (!"RECORDED".equals(result.path("state").asString()) || !result.path("current").asBoolean()
                || !directive.equals(read(result.path("directive"), ReleaseBundleActivationDirective.class)))
            throw ReleaseBundleError.RUNTIME_EVIDENCE.conflict();
    }
    private void attest(Calls session, Target target, ReleaseBundleActivationDirective directive) {
        for (String url : target.urls()) session.post(url, applicationPath(directive) + "/attest", null);
    }
    private Optional<List<JourneyBundleExecutionAck>> executeJourney(Calls session, Target target,
            ReleaseBundleActivationDirective directive, ReleaseManifest member, Instant deadline) {
        var probe = probe(target, directive, member, deadline);
        String url = target.urls().getFirst();
        String path = PROBES + "/" + directive.bundle().campaignId() + "/" + probe.probeId();
        session.post(url, PROBES, probe);
        session.post(url, path + ":dispatch", null);
        var result = session.request(url, path, false, null, false);
        if (!probe.equals(read(result.path("probe"), JourneyBundleExecutionProbe.class)))
            throw ReleaseBundleError.RUNTIME_EVIDENCE.conflict();
        if (!"READY".equals(result.path("state").asString())) return Optional.empty();
        var acks = read(result.path("acknowledgements"), JourneyBundleExecutionAck[].class);
        if (acks.length != target.parallelism() || Arrays.stream(acks).anyMatch(a -> !probe.equals(a.probe()))
                || Arrays.stream(acks).map(JourneyBundleExecutionAck::subtaskIndex).distinct().count() != acks.length)
            throw ReleaseBundleError.RUNTIME_EVIDENCE.conflict();
        return Optional.of(List.of(acks));
    }

    /** 探针时段以持久授权时刻为原点；重启可重建原文，过期时段不能再次推进。 */
    private JourneyBundleExecutionProbe probe(Target target, ReleaseBundleActivationDirective directive,
            ReleaseManifest member, Instant deadline) {
        Instant now = clock.instant();
        long period = Duration.between(directive.authorizedAt(), now).dividedBy(PROBE_PERIOD);
        Instant start = directive.authorizedAt().plus(PROBE_PERIOD.multipliedBy(Math.max(0, period)));
        Instant end = start.plus(PROBE_PERIOD).isBefore(deadline) ? start.plus(PROBE_PERIOD) : deadline;
        if (!now.isBefore(end)) throw ReleaseBundleError.OPERATION_EXPIRED.conflict();
        String idMaterial = json.writeValueAsString(List.of(directive.signature(), member.manifestId(), start, end,
                target.jobId(), target.parallelism(), target.participationKeyId(), keys.activeKeyId()));
        String id = Digests.sha256Hex(idMaterial);
        var bundle = directive.bundle();
        var unsigned = new JourneyBundleExecutionProbe(id, bundle.tenantId(), bundle.campaignId(), bundle.environment(),
                bundle.cell(), member.namespace(), member.manifestId(), directive.activationSequence(), directive.signature(),
                target.jobId(), target.parallelism(), target.participationKeyId(), start, end, keys.activeKeyId(), "");
        return JourneyBundleExecutionProbeSigner.sign(keys.activePrivateKey(), unsigned);
    }

    private Target authority(ReleaseBundleManifest bundle) {
        var decision = bundle.members().stream().filter(member -> DECISION.equals(member.runtime())).findFirst();
        if (decision.isPresent()) return target(bundle, decision.get());
        var candidates = targets.values().stream().filter(t -> DECISION.equals(t.runtime()) && Boolean.TRUE.equals(t.admission())
                && t.environment().equals(bundle.environment()) && t.cell().equals(bundle.cell())).toList();
        if (candidates.size() != 1) throw ReleaseBundleError.RUNTIME_CONFIG.conflict();
        return candidates.getFirst();
    }
    private Target target(ReleaseBundleManifest bundle, ReleaseManifest member) {
        var target = targets.get(new Location(bundle.environment(), bundle.cell(), member.runtime(), member.namespace()));
        if (target == null) throw ReleaseBundleError.RUNTIME_CONFIG.conflict();
        return target;
    }
    private static String applicationPath(ReleaseBundleActivationDirective directive) {
        return DECISION_ROOT + "/release-bundle-applications/" + directive.bundle().campaignId() + "/" + directive.activationSequence();
    }
    private <T> T read(JsonNode node, Class<T> type) {
        try {
            T parsed = json.treeToValue(node, type);
            if (parsed == null) throw ReleaseBundleError.RUNTIME_EVIDENCE.conflict();
            return parsed;
        } catch (JacksonException invalid) { throw ReleaseBundleError.RUNTIME_EVIDENCE.conflict(); }
    }

    /** 每次尝试共享耗时上限，重试仅在操作层发起；任何模糊响应都保留未知结果。 */
    private final class Calls {
        private final long until = System.nanoTime() + PASS_TIMEOUT.toNanos();
        JsonNode post(String url, String path, Object body) { return request(url, path, true, body, false); }
        JsonNode request(String url, String path, boolean post, Object body, boolean absenceAllowed) {
            long nanos = Math.min(CALL_TIMEOUT.toNanos(), until - System.nanoTime());
            if (nanos <= 0) throw ReleaseBundleError.RUNTIME_UNAVAILABLE.conflict();
            var builder = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofNanos(nanos))
                    .header("Accept", "application/json");
            if (post) builder.header("Content-Type", "application/json").POST(body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var scope = TenantContextHolder.requireCurrent();
            String authorization = credentials.authorization(scope.tenantId().value(), dev);
            if (authorization != null) builder.header("Authorization", authorization);
            else {
                builder.header("X-Dev-Tenant-Id", scope.tenantId().value()).header("X-Dev-Actor-Id", "control-bundle-release")
                        .header("X-Dev-Permissions", "runtime:warm,runtime:activate,bundle:apply")
                        .header("X-Dev-Organization-Ids", String.join(",", scope.organizations()))
                        .header("X-Dev-Shop-Ids", String.join(",", scope.shops()));
            }
            var bytes = new ByteArrayOutputStream();
            var pending = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArrayConsumer(chunk ->
                    chunk.ifPresent(data -> {
                        if (data.length > MAX_REPLY - bytes.size()) throw ReleaseBundleError.RUNTIME_EVIDENCE.conflict();
                        bytes.writeBytes(data);
                    })));
            try {
                var response = pending.get(nanos, TimeUnit.NANOSECONDS);
                if (bytes.size() == 0) throw ReleaseBundleError.RUNTIME_EVIDENCE.conflict();
                var data = json.readTree(bytes.toByteArray());
                if (response.statusCode() >= 200 && response.statusCode() < 300 && data != null) return data;
                String reason = data == null ? "" : data.path("code").asString();
                if (absenceAllowed && response.statusCode() == 404 && ABSENT.equals(reason)) return null;
                if (response.statusCode() == 409 && reason.matches("[A-Z][A-Z0-9_]{1,94}"))
                    throw new ConflictException(reason, "运行端尚未满足本次组合发布条件");
                throw ReleaseBundleError.RUNTIME_UNAVAILABLE.conflict();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw ReleaseBundleError.RUNTIME_UNAVAILABLE.conflict();
            } catch (ExecutionException | TimeoutException | JacksonException failed) {
                throw ReleaseBundleError.RUNTIME_UNAVAILABLE.conflict();
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
        }
    }
    private record Location(String environment, String cell, String runtime, String namespace) {}
    private record Target(String environment, String cell, String runtime, String namespace, List<String> urls,
            Boolean admission, String jobId, Integer parallelism, String participationKeyId) {
        String root() { return DECISION.equals(runtime) ? DECISION_ROOT : JOURNEY_ROOT; }
    }
    private record Apply(long expectedAppliedSequence, Instant applyBefore, List<JourneyBundleExecutionAck> journeyAcks) {}
}
