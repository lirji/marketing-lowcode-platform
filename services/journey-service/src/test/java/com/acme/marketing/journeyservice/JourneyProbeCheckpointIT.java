package com.acme.marketing.journeyservice;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.event.JourneyBundleExecutionAckEvent;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 用提交制品、实际子任务和显式 checkpoint 验证事务回执的可见边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = JourneyProbeCheckpointIT.Cleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class JourneyProbeCheckpointIT {
    private static final String INPUT = "checkpoint-input", OUTPUT = "checkpoint-output";
    private static final String DB_ALIAS = "probe-db", BROKER = "probe-kafka:19092", JM_ALIAS = "probe-jm";
    private static final String FLINK_IMAGE = "flink:2.2.1-scala_2.12-java21";
    private static final String FLINK_PROPERTIES = """
            jobmanager.rpc.address: probe-jm
            rest.address: probe-jm
            taskmanager.numberOfTaskSlots: 2
            restart-strategy.type: none
            """;
    private static final KeyPair RELEASE = Ed25519.generateKeyPair(), COMPILER = Ed25519.generateKeyPair();
    private static final KeyPair EXECUTION = Ed25519.generateKeyPair(), PARTICIPATION = Ed25519.generateKeyPair();
    private static final Network NETWORK = Network.newNetwork();
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withNetwork(NETWORK).withNetworkAliases(DB_ALIAS)
            .withCommand("--performance-schema=OFF", "--innodb-buffer-pool-size=64M")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0")
            .withNetwork(NETWORK).withListener(BROKER)
            .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m");
    private static final GenericContainer<?> JM = flink("jobmanager").withNetworkAliases(JM_ALIAS)
            .withExposedPorts(8081).waitingFor(Wait.forHttp("/overview").forPort(8081));
    private static final GenericContainer<?> TM = flink("taskmanager");
    private static String jobId;
    private static String digest;

    private static GenericContainer<?> flink(String role) {
        return new GenericContainer<>(FLINK_IMAGE).withNetwork(NETWORK)
                .withEnv("FLINK_PROPERTIES", FLINK_PROPERTIES).withCommand(role);
    }

    /** 先按真实提交结果配置服务端信任目标，不能相信待验回执自报的身份。 */
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) throws Exception {
        Path jar = Path.of(System.getProperty("journey.probe.jar")).toRealPath();
        digest = "sha256:" + Digests.sha256Hex(Files.readAllBytes(jar));
        MYSQL.start();
        KAFKA.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(INPUT, 2, (short) 1), new NewTopic(OUTPUT, 2, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
        JM.withEnv("KAFKA_BOOTSTRAP_SERVERS", BROKER)
                .withEnv("JOURNEY_INPUT_TOPIC", INPUT).withEnv("JOURNEY_OUTPUT_TOPIC", OUTPUT)
                .withEnv("FLINK_CHECKPOINT_INTERVAL_MS", "600000")
                .withEnv("JOURNEY_RUNTIME_DB_URL", "jdbc:mysql://" + DB_ALIAS + ":3306/" + MYSQL.getDatabaseName()
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC")
                .withEnv("JOURNEY_RUNTIME_DB_USER", MYSQL.getUsername()).withEnv("JOURNEY_RUNTIME_DB_PASSWORD", MYSQL.getPassword())
                .withEnv("JOURNEY_RELEASE_TRUSTED_KEY_ID", "release-key").withEnv("JOURNEY_RELEASE_PUBLIC_KEY_BASE64", publicKey(RELEASE))
                .withEnv("JOURNEY_COMPILER_TRUSTED_KEY_ID", "compiler-key").withEnv("JOURNEY_COMPILER_PUBLIC_KEY_BASE64", publicKey(COMPILER))
                .withEnv("JOURNEY_PARTICIPATION_TRUSTED_KEY_ID", "participation").withEnv("JOURNEY_PARTICIPATION_PUBLIC_KEY_BASE64", publicKey(PARTICIPATION))
                .withEnv("JOURNEY_EXECUTION_ACK_KEY_ID", "execution").withEnv("JOURNEY_EXECUTION_ACK_PUBLIC_KEY_BASE64", publicKey(EXECUTION))
                .withEnv("JOURNEY_EXECUTION_ACK_PRIVATE_KEY_BASE64", Base64.getEncoder().encodeToString(EXECUTION.getPrivate().getEncoded()))
                .withCopyFileToContainer(MountableFile.forHostPath(jar), "/tmp/journey.jar").start();
        TM.start();
        var result = JM.execInContainer("timeout", "90", "flink", "run", "-d", "/tmp/journey.jar");
        assertEquals(0, result.getExitCode(), result.getStderr());
        var match = Pattern.compile("JobID ([0-9a-f]{32})").matcher(result.getStdout());
        assertTrue(match.find(), "隔离集群未返回实际作业 ID");
        jobId = match.group(1);

        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.flyway.user", MYSQL::getUsername);
        r.add("spring.flyway.password", MYSQL::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("marketing.release.trusted-key-id", () -> "release-key");
        r.add("marketing.release.public-key-base64", () -> publicKey(RELEASE));
        r.add("marketing.compiler.trusted-key-id", () -> "compiler-key");
        r.add("marketing.compiler.public-key-base64", () -> publicKey(COMPILER));
        r.add("marketing.journey.execution-target.job-id", () -> jobId);
        r.add("marketing.journey.execution-target.build-digest", () -> digest);
        r.add("marketing.journey.execution-target.parallelism", () -> 2);
        r.add("marketing.journey.execution-target.participation-key-id", () -> "participation");
        r.add("marketing.journey.execution-ack.trusted-key-id", () -> "execution");
        r.add("marketing.journey.execution-ack.public-key-base64", () -> publicKey(EXECUTION));
        r.add("marketing.kafka.consumers-enabled", () -> true);
        r.add("marketing.journey.output-topic", () -> OUTPUT);
        r.add("marketing.journey.probe-delivery.enabled", () -> true);
        r.add("marketing.journey.probe-delivery.input-topic", () -> INPUT);
        r.add("management.otlp.metrics.export.enabled", () -> false);
    }

    public static final class Cleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return org.springframework.core.Ordered.HIGHEST_PRECEDENCE; }
        @Override public void afterTestClass(TestContext context) {
            TM.stop(); JM.stop(); KAFKA.stop(); MYSQL.stop(); NETWORK.close();
        }
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void onlyCompletedCheckpointBecomesDurableSubtaskCoverage() throws Exception {
        String tenant = "checkpoint-test";
        var fixture = new JourneyProbeHttpFixture(port, tenant, mapper, RELEASE, COMPILER, HttpClient.newHttpClient());
        Instant issued = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var probe = fixture.install("checkpoint", jobId, issued.minusSeconds(1), issued);
        String path = "/api/v1/journey-runtime/execution-probes";
        String receiptPath = path + "/campaign/" + probe.probeId();
        checked(fixture, fixture.request(path).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(probe))));
        checked(fixture, fixture.request(receiptPath + ":dispatch").POST(HttpRequest.BodyPublishers.noBody()));

        // 旁路只读消费者看到同一探针的两个真实子任务输出后，服务仍须等待事务提交。
        Set<Integer> subtasks = new HashSet<>();
        try (var observer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "isolation.level", "read_uncommitted", "enable.auto.commit", false, "auto.offset.reset", "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            var partitions = List.of(new TopicPartition(OUTPUT, 0), new TopicPartition(OUTPUT, 1));
            observer.assign(partitions); observer.seekToBeginning(partitions);
            long end = System.nanoTime() + Duration.ofSeconds(50).toNanos();
            while (subtasks.size() < 2 && System.nanoTime() < end) {
                for (var record : observer.poll(Duration.ofMillis(250))) {
                    var ack = mapper.readValue(record.value(), JourneyBundleExecutionAckEvent.class).ack();
                    assertEquals(probe, ack.probe()); assertEquals(jobId, ack.jobId()); assertEquals(digest, ack.buildDigest());
                    subtasks.add(ack.subtaskIndex());
                }
            }
        }
        assertEquals(Set.of(0, 1), subtasks);
        var pending = checked(fixture, fixture.request(receiptPath).GET());
        assertEquals("PENDING", pending.path("state").asString());
        assertEquals(0, pending.path("acknowledgements").size());

        var checkpoint = JM.execInContainer("timeout", "45", "flink", "checkpoint", jobId);
        assertEquals(0, checkpoint.getExitCode(), checkpoint.getStderr());
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode receipt;
        do {
            receipt = checked(fixture, fixture.request(receiptPath).GET());
            if ("READY".equals(receipt.path("state").asString())) break;
            Thread.sleep(150);
        } while (System.nanoTime() < end);
        assertEquals("READY", receipt.path("state").asString());
        var acks = receipt.path("acknowledgements");
        assertEquals(2, acks.size());
        assertEquals(0, acks.get(0).path("subtaskIndex").asInt());
        assertEquals(1, acks.get(1).path("subtaskIndex").asInt());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mk_journey_dispatch_outbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mk_journey_runtime_activation", Integer.class));
    }

    private JsonNode checked(JourneyProbeHttpFixture fixture, HttpRequest.Builder request) throws Exception {
        var result = fixture.send(request);
        assertEquals(200, result.statusCode(), result.body());
        return mapper.readTree(result.body());
    }

    private static String publicKey(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }
}
