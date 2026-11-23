package com.acme.marketing.journeyservice;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.time.SqlTime;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 从已有已审批、已暂存清单开始，使用独立服务进程及真实 HTTP、Flink 和 MySQL 验收。 */
final class BundleReleaseScenario implements AutoCloseable {
    private static final String TENANT = "bundle-e2e", ACTOR = "bundle-pipeline", CAMPAIGN = "atomic-e2e";
    private static final String PATH = "/api/v1/campaigns/" + CAMPAIGN + "/release-bundles";
    // 隔离验收运行打包的 JVM 制品；此标识用于模拟部署方的 OCI 声明。
    private static final String IMAGE = "sha256:" + "1".repeat(64);
    private final List<Process> processes = new ArrayList<>();
    private final MySQLContainer mysql;
    private final int journey;
    private final String job, digest, kafka, inputTopic;
    private final KeyPair release, compiler, participation, execution, ack;
    private final KeyPair deployment = Ed25519.generateKeyPair(), replica = Ed25519.generateKeyPair(), secondReplica = Ed25519.generateKeyPair();
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Path root = Path.of(System.getProperty("bundle.repository")).toAbsolutePath();
    private final Path logs = Path.of("target","bundle-acceptance");
    private JdbcTemplate controlDb;
    private com.sun.net.httpserver.HttpServer externalCatalog;
    private final java.util.concurrent.atomic.AtomicInteger catalogReads = new java.util.concurrent.atomic.AtomicInteger();
    private int decision, secondDecision, control;

    BundleReleaseScenario(MySQLContainer mysql, int journey, String job, String digest, String kafka, String inputTopic, ObjectMapper json,
            KeyPair release, KeyPair compiler, KeyPair participation, KeyPair execution, KeyPair ack) {
        this.mysql=mysql; this.journey=journey; this.job=job; this.digest=digest; this.json=json;
        this.kafka=kafka; this.inputTopic=inputTopic; this.release=release; this.compiler=compiler; this.participation=participation; this.execution=execution; this.ack=ack;
    }

    void verify() throws Exception {
        Files.createDirectories(logs);
        startServices();
        String now = SqlTime.format(Instant.now());
        controlDb.update("INSERT INTO mk_campaign(tenant_id,campaign_id,name,objective,status,organization_id,shop_id,created_at,updated_at) VALUES (?,?,?,?,'DRAFT','org','shop',?,?)",
                TENANT,CAMPAIGN,"组合发布隔离验收","准入与回滚",now,now);
        registerInventory();
        if (Boolean.getBoolean("bundle.browser")) {
            install("decision",1); install("journey",1); runBrowser();
        } else {
            prepare(1); operate("ACTIVATE",1,0);
        }
        var first = admission("first");
        assertEquals(1,first.path("activationSequence").asLong());
        assertEquals(first,admission(secondDecision,"first"),"另一副本应恢复完全相同的已签准入事实");
        prepare(2);
        // 在第二版走真实排期、暂停、恢复和机器领取，以免把人工激活回归当作调度验收。
        String schedules = PATH + "/schedules";
        var task = call(control,"POST",schedules,Map.of("version",2,"expectedAppliedSequence",1,
                "dueAt",Instant.now().plusSeconds(3).toString()),"schedule-v2");
        String item = schedules + "/" + task.path("scheduleId").asString();
        var paused = call(control,"POST",item + ":pause?expectedRevision=1",null,null);
        assertEquals("PAUSED",paused.path("state").asString());
        Thread.sleep(3100);
        assertFalse(call(control,"POST","/internal/v1/release-bundle-schedules:tick",null,null).path("claimed").asBoolean());
        var resumed = call(control,"POST",item + ":resume?expectedRevision=2",null,null);
        assertEquals("SCHEDULED",resumed.path("state").asString());
        Instant until = Instant.now().plusSeconds(120);
        JsonNode schedule;
        do {
            call(control,"POST","/internal/v1/release-bundle-schedules:tick",null,null);
            schedule = call(control,"GET",schedules + "?version=2",null,null).get(0);
            if ("APPLIED".equals(schedule.path("state").asString())) break;
            Thread.sleep(900);
        } while (Instant.now().isBefore(until));
        assertEquals("APPLIED",schedule.path("state").asString(),schedule.toString());
        var second = admission("second");
        assertEquals(2,second.path("bundleVersion").asLong());
        assertEquals(first,admission("first"));
        operate("ROLLBACK",1,2);
        var after = admission("after-rollback");
        assertEquals(3,after.path("activationSequence").asLong());
        assertEquals(1,after.path("bundleVersion").asLong());
        assertEquals(second,admission("second"));
        assertEquals(first,admission("first"));
        assertRunsBoundJourney(second);
        assertRunsBoundJourney(after);
        assertTrue(catalogReads.get() >= 3, "每次整体切换都须经资金服务复核 SKU");
    }

    private JsonNode admission(String id) throws Exception {
        return admission(decision,id);
    }

    private JsonNode admission(int endpoint, String id) throws Exception {
        return call(endpoint,"POST","/api/v1/decisions/bundle-participations",
                Map.of("campaignId",CAMPAIGN,"participationId",id,"subjectToken","member-" + id),null);
    }

    /** 回滚后用旧参与和新参与各启动一次，直到真实 Flink 输出被 Journey 存为对应定义版本。 */
    private void assertRunsBoundJourney(JsonNode receipt) throws Exception {
        var bound = json.treeToValue(receipt,ReleaseBundleParticipation.class);
        String member = "journey-" + bound.bundleVersion();
        String definition = "definition-" + member;
        String enrollment = bound.enrollmentId(member);
        var message = Map.of("tenantId",TENANT,"enrollmentId",enrollment,"subjectToken","member-" + bound.participationId(),
                "planReference",Map.of("artifactId",member,"generation",bound.bundleVersion(),"activationSequence",bound.activationSequence(),
                        "journeyId",definition,"journeyVersion",1),"bundleParticipation",bound,"signal",
                Map.of("type","START","signalId","start-" + bound.participationId(),"occurredAtEpochMillis",System.currentTimeMillis()));
        try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<String,String>(
                Map.of("bootstrap.servers",kafka,"max.block.ms",5000),
                new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.StringSerializer())) {
            producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(inputTopic,TENANT + ":" + enrollment,
                    json.writeValueAsString(message))).get(10,TimeUnit.SECONDS);
        }
        var projection = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword()));
        Instant end = Instant.now().plusSeconds(45);
        do {
            var matches = projection.queryForList("SELECT journey_id FROM mk_enrollment WHERE tenant_id=? AND enrollment_id=?",
                    String.class,TENANT,enrollment);
            if (!matches.isEmpty()) { assertEquals(List.of(definition),matches); return; }
            Thread.sleep(250);
        } while (Instant.now().isBefore(end));
        fail("Flink did not project pinned definition " + definition);
    }

    private void operate(String action, int version, int previous) throws Exception {
        var operation = call(control,"POST",PATH + "/operations",Map.of("version",version,"action",action,
                "expectedAppliedSequence",previous),"operation-" + previous);
        String retry = PATH + "/operations/" + operation.path("operationId").asString() + ":retry";
        Instant until = Instant.now().plusSeconds(100);
        while ("PENDING".equals(operation.path("state").asString()) && Instant.now().isBefore(until)) {
            Thread.sleep(900);
            operation = call(control,"POST",retry,null,null);
        }
        assertEquals("APPLIED",operation.path("state").asString(),operation.toString());
    }

    private void prepare(int version) throws Exception {
        String offer = install("decision",version), plan = install("journey",version);
        var bundle = call(control,"POST",PATH,Map.of("expectedVersion",version-1,"manifestIds",List.of(offer,plan)),
                "prepare-" + version);
        assertEquals(version,bundle.path("version").asLong());
    }

    private String install(String runtime, int version) throws Exception {
        Instant at = Instant.now().minusSeconds(5), expires = at.plusSeconds(1200);
        String id = runtime + "-" + version, definition = "definition-" + id, approval = "case-" + id;
        boolean isJourney = runtime.equals("journey");
        byte[] bytes;
        String graphJson = "{}";
        if (isJourney) {
            var end = new com.acme.marketing.journey.JourneyNode("end",com.acme.marketing.journey.JourneyNode.Type.END,Map.of(),Map.of());
            bytes = json.writeValueAsBytes(new com.acme.marketing.journey.JourneyPlan(definition,1,"end",Map.of("end",end),5,1,Duration.ofDays(1)));
        } else {
            var graph = new com.acme.marketing.lowcode.model.GraphDefinition(definition,
                    com.acme.marketing.lowcode.model.Dialect.OFFER_DECISION_DAG,"1.0.0",List.of(
                    new com.acme.marketing.lowcode.model.GraphNode("start","offer.start","1.0.0",Map.of()),
                    new com.acme.marketing.lowcode.model.GraphNode("discount","offer.fixed","1.0.0",
                            Map.of("amountMinor","1000","benefitDefinitionVersion","benefit@1",
                                    "validFrom",at.toString(),"validTo",expires.toString())),
                    new com.acme.marketing.lowcode.model.GraphNode("end","offer.end","1.0.0",Map.of())),
                    List.of(new com.acme.marketing.lowcode.model.GraphEdge("e1","start","next","discount","in"),
                            new com.acme.marketing.lowcode.model.GraphEdge("e2","discount","next","end","in")),Map.of(),Map.of());
            graphJson = json.writeValueAsString(graph);
            bytes = json.writeValueAsBytes(new com.acme.marketing.decision.runtime.OfferPolicyGraphCompiler().compile(graph));
        }
        String source = "sha256:" + Digests.sha256Hex(definition), checksum = "sha256:" + Digests.sha256Hex(bytes);
        String type = isJourney ? "JOURNEY_PLAN" : "OFFER_POLICY", abi = isJourney ? "marketing-journey-plan/1" : "marketing-offer-policy/1";
        String signature = ArtifactAttestation.sign(compiler.getPrivate(),TENANT,id,definition,1,type,abi,checksum,source);
        var artifact = new ArtifactReference(id,type,"s3://artifacts/" + id,checksum,source,"compiler-key",signature,abi,definition,1);
        var member = new ReleaseManifestSigner().sign(new ReleaseManifest(id,new TenantId(TENANT),"local","cell-a",runtime,"main",
                version,0,List.of(),List.of(),List.of(artifact),Map.of(),0,at,expires,"author",List.of(approval),at,""),release.getPrivate());
        String stamp = SqlTime.format(at);
        controlDb.update("INSERT INTO mk_definition_version(tenant_id,definition_id,campaign_id,version_no,dialect,graph_json,semantic_hash,status,created_by,created_at,updated_at) VALUES (?,?,?,1,?,?,?,'APPROVED','author',?,?)",
                TENANT,definition,CAMPAIGN,isJourney ? "JOURNEY_DAG" : "OFFER_DECISION_DAG",graphJson,source,stamp,stamp);
        controlDb.update("INSERT INTO mk_approval_case(tenant_id,case_id,definition_id,definition_version,submitted_by,required_roles,status,created_at,updated_at) VALUES (?,?,?,1,'author','LEGAL','APPROVED',?,?)",
                TENANT,approval,definition,stamp,stamp);
        controlDb.update("INSERT INTO mk_release_manifest(tenant_id,manifest_id,environment_name,cell_id,runtime_name,namespace_name,generation_no,state_name,manifest_json,created_at) VALUES (?,?,'local','cell-a',?,'main',?,'STAGED',?,?)",
                TENANT,id,runtime,version,json.writeValueAsString(member),stamp);
        controlDb.update("INSERT INTO mk_release_slot(tenant_id,environment_name,cell_id,runtime_name,namespace_name,stable_generation,desired_generation,latest_generation,activation_sequence,updated_at) VALUES (?,'local','cell-a',?,'main',0,0,?,0,?) ON DUPLICATE KEY UPDATE latest_generation=?",
                TENANT,runtime,version,stamp,version);
        var body = Map.of(isJourney ? "releaseKeyId" : "keyId","release-key","manifest",member,
                isJourney ? "artifactBase64" : "policyArtifactBase64",encoded(bytes));
        call(isJourney ? journey : decision,"PUT",isJourney ? "/api/v1/journey-runtime/manifest" : "/api/v1/decisions/runtime/manifest",body,null);
        return id;
    }

    private void registerInventory() throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        var inventory = new DecisionDeploymentInventory("local","cell-a","main",1,now,now.plusSeconds(300),
                List.of(new DecisionDeploymentInventory.Replica("decision-instance",IMAGE,"replica"),
                        new DecisionDeploymentInventory.Replica("decision-peer",IMAGE,"peer-key")),"deploy","").sign(deployment.getPrivate());
        call(decision,"PUT","/api/v1/decisions/runtime/deployment-inventory",inventory,null);
    }

    private JsonNode call(int port,String method,String path,Object body,String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin(port) + path)).timeout(Duration.ofSeconds(40))
                .header("Content-Type","application/json").header("X-Dev-Tenant-Id",TENANT)
                .header("X-Dev-Actor-Id",ACTOR).header("X-Dev-Permissions","*")
                .header("X-Dev-Organization-Ids","org").header("X-Dev-Shop-Ids","shop");
        if (key != null) request.header("Idempotency-Key",key);
        if (body instanceof DecisionDeploymentInventory i) request.header("X-Inventory-Digest",i.contentDigest());
        request.method(method,body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> method + " " + path + " " + response.statusCode() + " " + response.body());
        return json.readTree(response.body());
    }

    private void startServices() throws Exception {
        // 只在本用例创建的 MySQL 容器内准备两个独立逻辑库，绝不读取本机连接配置。
        for (String database : List.of("bundle_decision","bundle_control","bundle_benefit")) {
            var created = mysql.execInContainer("mysql","-uroot","-p" + mysql.getPassword(),"-e",
                    "CREATE DATABASE " + database + "; GRANT ALL ON " + database + ".* TO '" + mysql.getUsername() + "'@'%'");
            assertEquals(0,created.getExitCode(),created.getStderr());
        }
        var common = new HashMap<String,String>();
        common.put("MARKETING_COMPILER_TRUSTED_KEY_ID","compiler-key"); common.put("MARKETING_COMPILER_PUBLIC_KEY_BASE64",pub(compiler));
        common.put("MARKETING_RUNTIME_ACK_SIGNING_KEY_ID","runtime-ack"); common.put("MARKETING_RUNTIME_ACK_PRIVATE_KEY_BASE64",priv(ack));
        common.put("MARKETING_RUNTIME_ACK_PUBLIC_KEY_BASE64",pub(ack));
        var d = new HashMap<>(common);
        d.put("MARKETING_RELEASE_TRUSTED_KEY_ID","release-key"); d.put("MARKETING_RELEASE_PUBLIC_KEY_BASE64",pub(release));
        d.put("MARKETING_PARTICIPATION_TRUSTED_KEY_ID","participation"); d.put("MARKETING_PARTICIPATION_PUBLIC_KEY_BASE64",pub(participation));
        d.put("MARKETING_PARTICIPATION_SIGNING_KEY_ID","participation"); d.put("MARKETING_PARTICIPATION_PRIVATE_KEY_BASE64",priv(participation));
        d.put("MARKETING_PARTICIPATION_SIGNING_PUBLIC_KEY_BASE64",pub(participation));
        d.put("DECISION_DEPLOYMENT_TRUSTED_PUBLIC_KEYS","deploy=" + pub(deployment));
        d.put("DECISION_DEPLOYMENT_PIPELINE_ACTOR",ACTOR); d.put("DECISION_DEPLOYMENT_PIPELINE_TENANT",TENANT);
        d.put("DECISION_EXECUTION_INSTANCE_ID","decision-instance"); d.put("DECISION_EXECUTION_IMAGE_DIGEST",IMAGE);
        d.put("DECISION_EXECUTION_KEY_ID","replica"); d.put("DECISION_EXECUTION_PRIVATE_KEY_BASE64",priv(replica));
        d.put("DECISION_EXECUTION_TRUSTED_PUBLIC_KEYS","replica=" + pub(replica) + ",peer-key=" + pub(secondReplica));
        d.put("DECISION_JOURNEY_EXECUTION_TRUSTED_PUBLIC_KEYS","execution=" + pub(execution));
        d.put("DECISION_JOURNEY_EXECUTION_TARGETS",json.writeValueAsString(Map.of("main",Map.of("jobId",job,"parallelism",2,"buildDigest",digest))));
        d.put("MARKETING_RUNTIME_ID","decision-instance");
        decision = start("offer-decision-service","bundle_decision",d);
        var peer = new HashMap<>(d);
        peer.put("MARKETING_RUNTIME_ID","decision-peer");
        peer.put("DECISION_EXECUTION_INSTANCE_ID","decision-peer");
        peer.put("DECISION_EXECUTION_KEY_ID","peer-key");
        peer.put("DECISION_EXECUTION_PRIVATE_KEY_BASE64",priv(secondReplica));
        secondDecision = start("offer-decision-service","bundle_decision",peer);
        // SKU 目录属于外部仓库；本仓库的权益资金服务、版本检查与出站 HTTP 使用真实实现。
        externalCatalog = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        externalCatalog.createContext("/internal/v1/catalog/skus", exchange -> {
            catalogReads.incrementAndGet();
            byte[] body = json.writeValueAsBytes(List.of(Map.of("skuId","sku-active","benefitType","COUPON",
                    "status","ACTIVE","enabled",true,"validityType","RELATIVE","relativeDays",7,"version",1)));
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        externalCatalog.start();
        int funds = start("benefit-funding-service","bundle_benefit",Map.of("BENEFIT_CENTER_BASE_URL",origin(externalCatalog.getAddress().getPort())));
        new JdbcTemplate(new DriverManagerDataSource(dbUrl("bundle_benefit"),mysql.getUsername(),mysql.getPassword()))
                .update("INSERT INTO mk_benefit_definition(tenant_id,benefit_id,version_no,name_text,status_name,resource_key,benefit_sku_id,policy_json,created_by,created_at) VALUES (?,'benefit',1,'验收权益','ACTIVE','','sku-active',?,'author',?)",
                        TENANT,json.writeValueAsString(Map.of("type","COUPON")),SqlTime.format(Instant.now()));
        var c = new HashMap<>(common);
        c.put("MARKETING_BENEFIT_FUNDING_BASE_URL",origin(funds));
        c.put("MARKETING_RELEASE_SIGNING_KEY_ID","release-key"); c.put("MARKETING_RELEASE_PRIVATE_KEY_BASE64",priv(release));
        c.put("MARKETING_RELEASE_PUBLIC_KEY_BASE64",pub(release)); c.put("MARKETING_RUNTIME_ACK_TRUSTED_KEY_ID","runtime-ack");
        c.put("MARKETING_BUNDLE_RUNTIME_TARGETS",json.writeValueAsString(List.of(
                Map.of("environment","local","cell","cell-a","runtime","decision","namespace","main","urls",List.of(origin(decision),origin(secondDecision)),"admission",true),
                Map.of("environment","local","cell","cell-a","runtime","journey","namespace","main","urls",List.of(origin(journey)),
                        "jobId",job,"parallelism",2,"participationKeyId","participation"))));
        control = start("marketing-control-service","bundle_control",c);
        controlDb = new JdbcTemplate(new DriverManagerDataSource(dbUrl("bundle_control"),mysql.getUsername(),mysql.getPassword()));
    }

    private String dbUrl(String schema) {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    private int start(String module,String db,Map<String,String> settings) throws Exception {
        int port;
        try (var socket = new ServerSocket(0,1,InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        Path jar = root.resolve("services/" + module + "/target/" + module + "-1.0.0-SNAPSHOT-exec.jar");
        var pb = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xmx220m","-XX:ActiveProcessorCount=2","-XX:TieredStopAtLevel=1","-jar",jar.toString());
        var env = pb.environment();
        env.putAll(settings); env.put("PORT",Integer.toString(port)); env.put("SERVER_ADDRESS","127.0.0.1");
        env.put("MARKETING_SECURITY_MODE","DEV"); env.put("MARKETING_DEV_HEADERS_ENABLED","true");
        env.put("SPRING_DATASOURCE_URL",dbUrl(db)); env.put("SPRING_DATASOURCE_USERNAME",mysql.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD",mysql.getPassword()); env.put("SPRING_FLYWAY_USER",mysql.getUsername());
        env.put("SPRING_FLYWAY_PASSWORD",mysql.getPassword()); env.put("MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED","false");
        var suffix = settings.get("DECISION_EXECUTION_INSTANCE_ID");
        var log = logs.resolve(module + (suffix == null ? "" : "-" + suffix) + ".log");
        var proc = pb.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        processes.add(proc);
        Instant end = Instant.now().plusSeconds(55);
        while (proc.isAlive() && Instant.now().isBefore(end)) {
            try {
                var result = http.send(HttpRequest.newBuilder(URI.create(origin(port) + "/actuator/health/liveness"))
                        .timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding());
                if (result.statusCode() == 200) return port;
            } catch (java.io.IOException starting) { /* 进程正在监听或迁移。 */ }
            Thread.sleep(150);
        }
        fail(module + " startup failed, inspect " + log); return port;
    }

    private void runBrowser() throws Exception {
        var pb = new ProcessBuilder("pnpm","--filter","@marketing/console","exec","playwright","test",
                "real-bundle-release.spec.ts","--project=chromium","--workers=1","--retries=0","--output=" + logs.resolve("browser-results").toAbsolutePath())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(logs.resolve("browser.log").toFile());
        pb.environment().putAll(Map.of("BUNDLE_CONTROL_URL",origin(control),"BUNDLE_CAMPAIGN_ID",CAMPAIGN,
                "BUNDLE_VERSION","1","BUNDLE_MANIFEST_IDS","decision-1,journey-1","BUNDLE_TENANT_ID",TENANT,"BUNDLE_ORGANIZATIONS","org","BUNDLE_SHOPS","shop","BUNDLE_ACTOR_ID",ACTOR));
        var browser = pb.start(); processes.add(browser);
        assertTrue(browser.waitFor(205,TimeUnit.SECONDS),"browser timed out"); assertEquals(0,browser.exitValue(),"browser.log");
    }

    private static String origin(int port) { return "http://127.0.0.1:" + port; }
    private static String encoded(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static String pub(KeyPair kp) { return encoded(kp.getPublic().getEncoded()); }
    private static String priv(KeyPair kp) { return encoded(kp.getPrivate().getEncoded()); }

    @Override public void close() {
        if (externalCatalog != null) externalCatalog.stop(0);
        for (var p : processes.reversed()) if (p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(8,TimeUnit.SECONDS)) { p.destroyForcibly(); p.waitFor(3,TimeUnit.SECONDS); }
            } catch (InterruptedException interrupted) { p.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }
}
