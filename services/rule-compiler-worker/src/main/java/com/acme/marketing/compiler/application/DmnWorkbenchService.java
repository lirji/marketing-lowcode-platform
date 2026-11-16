package com.acme.marketing.compiler.application;

import static com.acme.marketing.compiler.application.DmnWorkbenchErrors.*;

import com.acme.marketing.decision.rule.DecisionTableAdapter;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import com.acme.marketing.platform.web.TenantContextHolder;
import com.acme.marketing.rule.dmn.KieDmnDecisionTableAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 无持久化副作用的受限 DMN 工作台；只在本进程内单准入，不宣称进程级强制终止。 */
@Service
public final class DmnWorkbenchService {
    private static final Semaphore ADMISSION = new Semaphore(1);
    private final DmnWorkbenchInput input;
    private final DecisionTableAdapter adapter;
    private final MeterRegistry meters;

    /** 复用真实 KIE 适配器与平台指标，禁止使用伪造分析/求值结果。 */
    @Autowired
    public DmnWorkbenchService(ObjectMapper mapper, MeterRegistry meters) {
        this(mapper, meters, new KieDmnDecisionTableAdapter());
    }

    DmnWorkbenchService(ObjectMapper mapper, MeterRegistry meters, DecisionTableAdapter adapter) {
        this.input = new DmnWorkbenchInput(mapper); this.meters = meters; this.adapter = adapter;
    }

    /** 当前权限每次检查；仅返回真实引擎识别出的缺口、重叠和诊断。 */
    public DecisionTableAdapter.Analysis analyze(JsonNode request) {
        TenantContextHolder.requireCurrent().requirePermission("definition:compile");
        return admitted("analyze", () -> {
            var prepared = input.read(request, false);
            return adapter.analyze(DmnWorkbenchInput.NAMESPACE, prepared.xml());
        });
    }

    /** 先编译合法模型再试算；大数结果转十进制字符串，避免浏览器 Number 丢失精度。 */
    public DecisionTableAdapter.Evaluation evaluate(JsonNode request) {
        TenantContextHolder.requireCurrent().requirePermission("definition:simulate");
        return admitted("evaluate", () -> {
            var prepared = input.read(request, true);
            var compilation = adapter.compile(DmnWorkbenchInput.NAMESPACE, DmnWorkbenchInput.MODEL, prepared.xml());
            if (!compilation.valid()) return new DecisionTableAdapter.Evaluation(false, Map.of(), compilation.messages());
            var result = adapter.evaluate(compilation.table(), DmnWorkbenchInput.DECISION, prepared.context());
            Map<String, Object> values = new LinkedHashMap<>();
            result.results().forEach((key, value) -> values.put(key, exact(value)));
            return new DecisionTableAdapter.Evaluation(result.success(), values, result.messages());
        });
    }

    private <T> T admitted(String operation, Supplier<T> work) {
        if (!ADMISSION.tryAcquire()) {
            meters.counter("marketing.dmn.workbench", "operation", operation, "result", "busy").increment();
            throw new DependencyUnavailableException(BUSY_CODE, BUSY_MESSAGE);
        }
        try {
            T result = work.get();
            meters.counter("marketing.dmn.workbench", "operation", operation, "result", "completed").increment();
            return result;
        } catch (RuntimeException failure) {
            meters.counter("marketing.dmn.workbench", "operation", operation, "result", "failed").increment();
            throw failure;
        } finally {
            ADMISSION.release();
        }
    }

    private static Object exact(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), exact(child)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(DmnWorkbenchService::exact).toList();
        return value;
    }
}
