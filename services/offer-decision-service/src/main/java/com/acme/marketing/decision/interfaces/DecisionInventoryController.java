package com.acme.marketing.decision.interfaces;

import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.decision.application.DecisionInventoryRepository.Position;
import com.acme.marketing.decision.application.DecisionInventoryService;
import com.acme.marketing.decision.deployment.DecisionInventoryJson;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;

/** 流水线先读登记位置，再提交一版完整签名清单；不暴露清单里的副本给普通租户。 */
@RestController
@RequestMapping("/api/v1/decisions/runtime/deployment-inventory")
public class DecisionInventoryController {
    private final DecisionInventoryService service;
    public DecisionInventoryController(DecisionInventoryService service) { this.service = service; }

    /** 首次登记前返回零序号及空摘要。 */
    @GetMapping public Position current() { return service.current(); }

    /** 限制流式请求体；在读取和解析清单前核对机器身份。 */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Position record(HttpServletRequest request) {
        service.requirePipeline();
        try {
            if (request.getContentLengthLong() > DecisionInventoryJson.MAX_DOCUMENT_BYTES)
                throw ReleaseBundleProtocolError.DECISION_INVENTORY_INVALID.invalid();
            var bytes = request.getInputStream().readNBytes(DecisionInventoryJson.MAX_DOCUMENT_BYTES + 1);
            var inventory = DecisionInventoryJson.read(bytes);
            String digest = request.getHeader("X-Inventory-Digest");
            if (digest != null && !inventory.contentDigest().equals(digest))
                throw ReleaseBundleProtocolError.DECISION_INVENTORY_INVALID.invalid();
            return service.record(inventory);
        } catch (IOException | JacksonException invalid) { throw ReleaseBundleProtocolError.DECISION_INVENTORY_INVALID.invalid(); }
    }
}
