package com.acme.marketing.rule.drools;

import com.acme.marketing.decision.rule.RuleEngineAdapter;
import com.acme.marketing.decision.rule.RuleFacts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.drools.model.codegen.ExecutableModelProject;
import org.kie.api.KieServices;
import org.kie.api.builder.Message;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.Results;
import org.kie.api.builder.model.KieBaseModel;
import org.kie.api.builder.model.KieModuleModel;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import com.acme.marketing.platform.crypto.Digests;

public final class DroolsRuleEngineAdapter implements RuleEngineAdapter {
    private static final String ENGINE_VERSION = "drools-10.2";

    @Override
    public CompilationResult compile(String namespace, String source, Duration budget) {
        if (namespace == null || namespace.isBlank() || source == null || source.isBlank()) {
            return new CompilationResult(false, null, List.of("namespace and DRL source are required"));
        }
        return within(budget, () -> compileNow(namespace, source),
                new CompilationResult(false, null, List.of("COMPILATION_TIMEOUT")));
    }

    @Override
    public EvaluationResult evaluate(
            CompiledRules rules, Map<String, Object> facts, int fireLimit, Duration budget) {
        if (rules == null || fireLimit <= 0) {
            throw new IllegalArgumentException("compiled rules and a positive fire limit are required");
        }
        return within(budget, () -> evaluateNow(rules, facts, fireLimit),
                new EvaluationResult(false, List.of(), List.of("EVALUATION_TIMEOUT")));
    }

    private static CompilationResult compileNow(String namespace, String source) {
        KieServices services = KieServices.Factory.get();
        String artifactId = "rules-" + Digests.sha256Hex(source).substring(0, 16);
        ReleaseId releaseId = services.newReleaseId("com.acme.marketing.rules", artifactId, "1.0.0");
        KieModuleModel moduleModel = services.newKieModuleModel();
        KieBaseModel baseModel = moduleModel.newKieBaseModel("marketingRules").setDefault(true).addPackage("*");
        baseModel.newKieSessionModel("marketingSession").setDefault(true);
        KieFileSystem fileSystem = services.newKieFileSystem();
        fileSystem.generateAndWritePomXML(releaseId);
        fileSystem.writeKModuleXML(moduleModel.toXML());
        fileSystem.write("src/main/resources/" + namespace.replaceAll("[^a-zA-Z0-9_-]", "_") + ".drl", source);
        KieBuilder builder = services.newKieBuilder(fileSystem).buildAll(ExecutableModelProject.class);
        Results results = builder.getResults();
        List<String> messages = results.getMessages().stream().map(Message::toString).toList();
        if (results.hasMessages(Message.Level.ERROR)) {
            return new CompilationResult(false, null, messages);
        }
        KieModule module = builder.getKieModule();
        if (!(module instanceof InternalKieModule internalModule)) {
            return new CompilationResult(false, null, List.of("UNSUPPORTED_KIE_MODULE"));
        }
        return new CompilationResult(true,
                new CompiledRules(namespace, internalModule.getBytes(), ENGINE_VERSION), messages);
    }

    private static EvaluationResult evaluateNow(CompiledRules rules, Map<String, Object> facts, int fireLimit) {
        KieServices services = KieServices.Factory.get();
        try {
            KieModule module = services.getRepository()
                    .addKieModule(services.getResources().newByteArrayResource(rules.executableModel()));
            KieContainer container = services.newKieContainer(module.getReleaseId());
            List<String> resultCodes = new ArrayList<>();
            KieSession session = container.newKieSession();
            try {
                session.setGlobal("resultCodes", resultCodes);
                session.insert(new RuleFacts(facts));
                int fired = session.fireAllRules(fireLimit);
                if (fired >= fireLimit) {
                    return new EvaluationResult(false, List.of(), List.of("FIRE_LIMIT_REACHED"));
                }
                return new EvaluationResult(!resultCodes.isEmpty(), resultCodes, List.of());
            } finally {
                session.dispose();
                container.dispose();
            }
        } catch (RuntimeException failure) {
            return new EvaluationResult(false, List.of(), List.of("EVALUATION_FAILED: " + failure.getMessage()));
        }
    }

    private static <T> T within(Duration budget, java.util.concurrent.Callable<T> task, T timeoutResult) {
        if (budget == null || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("budget must be positive");
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<T> future = executor.submit(task);
            try {
                return future.get(budget.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                return timeoutResult;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return timeoutResult;
            } catch (ExecutionException failure) {
                throw new IllegalStateException("rule operation failed", failure.getCause());
            }
        }
    }
}
