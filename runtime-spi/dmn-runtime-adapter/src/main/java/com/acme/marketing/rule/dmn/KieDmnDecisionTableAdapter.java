package com.acme.marketing.rule.dmn;

import com.acme.marketing.decision.rule.DecisionTableAdapter;
import com.acme.marketing.platform.crypto.Digests;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.Results;
import org.kie.api.builder.model.KieBaseModel;
import org.kie.api.builder.model.KieModuleModel;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieRuntimeFactory;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.validation.DMNValidator;
import org.kie.dmn.validation.DMNValidatorFactory;

public final class KieDmnDecisionTableAdapter implements DecisionTableAdapter {
    @Override
    public Analysis analyze(String namespace, String dmnXml) {
        List<DMNMessage> messages;
        DMNValidator validator = DMNValidatorFactory.newValidator();
        try {
            messages = validator.validate(new StringReader(dmnXml),
                    DMNValidator.Validation.VALIDATE_SCHEMA,
                    DMNValidator.Validation.VALIDATE_MODEL,
                    DMNValidator.Validation.VALIDATE_COMPILATION,
                    DMNValidator.Validation.ANALYZE_DECISION_TABLE);
        } finally {
            validator.dispose();
        }
        List<String> gaps = new ArrayList<>();
        List<String> overlaps = new ArrayList<>();
        List<String> all = new ArrayList<>();
        boolean valid = true;
        for (DMNMessage message : messages) {
            String rendered = message.getMessageType() + ": " + message.getText();
            all.add(rendered);
            String type = message.getMessageType().name();
            if (type.contains("GAP")) {
                gaps.add(rendered);
            }
            if (type.contains("OVERLAP")) {
                overlaps.add(rendered);
            }
            if (message.getLevel() == Message.Level.ERROR) {
                valid = false;
            }
        }
        return new Analysis(valid, gaps, overlaps, all);
    }

    @Override
    public Compilation compile(String namespace, String modelName, String dmnXml) {
        Analysis analysis = analyze(namespace, dmnXml);
        if (!analysis.valid()) {
            return new Compilation(false, null, analysis.messages());
        }
        KieServices services = KieServices.Factory.get();
        String digest = Digests.sha256Hex(dmnXml);
        ReleaseId releaseId = services.newReleaseId("com.acme.marketing.dmn", "dmn-" + digest.substring(0, 16), "1.0.0");
        KieModuleModel moduleModel = services.newKieModuleModel();
        KieBaseModel baseModel = moduleModel.newKieBaseModel("marketingDmn").setDefault(true).addPackage("*");
        baseModel.newKieSessionModel("marketingDmnSession").setDefault(true);
        KieFileSystem fileSystem = services.newKieFileSystem();
        fileSystem.generateAndWritePomXML(releaseId);
        fileSystem.writeKModuleXML(moduleModel.toXML());
        fileSystem.write("src/main/resources/" + digest.substring(0, 16) + ".dmn", dmnXml);
        KieBuilder builder = services.newKieBuilder(fileSystem).buildAll();
        Results results = builder.getResults();
        List<String> messages = results.getMessages().stream().map(Message::toString).toList();
        if (results.hasMessages(Message.Level.ERROR)) {
            return new Compilation(false, null, messages);
        }
        KieModule module = builder.getKieModule();
        if (!(module instanceof InternalKieModule internalModule)) {
            return new Compilation(false, null, List.of("UNSUPPORTED_KIE_MODULE"));
        }
        return new Compilation(true,
                new CompiledDecisionTable(namespace, modelName, internalModule.getBytes()), messages);
    }

    @Override
    public Evaluation evaluate(CompiledDecisionTable table, String decisionName, Map<String, Object> context) {
        KieServices services = KieServices.Factory.get();
        try {
            KieModule module = services.getRepository()
                    .addKieModule(services.getResources().newByteArrayResource(table.kjar()));
            KieContainer container = services.newKieContainer(module.getReleaseId());
            try {
                DMNRuntime runtime = KieRuntimeFactory.of(container.getKieBase()).get(DMNRuntime.class);
                DMNModel model = runtime.getModel(table.namespace(), table.modelName());
                if (model == null) {
                    return new Evaluation(false, Map.of(), List.of("DMN_MODEL_NOT_FOUND"));
                }
                DMNContext dmnContext = runtime.newContext();
                context.forEach(dmnContext::set);
                DMNResult result = runtime.evaluateByName(model, dmnContext, decisionName);
                List<String> messages = result.getMessages().stream().map(DMNMessage::getText).toList();
                return new Evaluation(!result.hasErrors(), result.getContext().getAll(), messages);
            } finally {
                container.dispose();
            }
        } catch (RuntimeException failure) {
            return new Evaluation(false, Map.of(), List.of("DMN_EVALUATION_FAILED: " + failure.getMessage()));
        }
    }
}
