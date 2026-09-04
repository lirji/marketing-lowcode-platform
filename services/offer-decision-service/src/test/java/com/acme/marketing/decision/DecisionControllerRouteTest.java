package com.acme.marketing.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.acme.marketing.decision.application.DecisionApplicationService;
import com.acme.marketing.decision.interfaces.DecisionController;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class DecisionControllerRouteTest {
    @Test
    void evaluateUsesTheContractualActionSuffixWithoutAnInsertedSlash() throws Exception {
        RequestMapping root = DecisionController.class.getAnnotation(RequestMapping.class);
        PostMapping action = DecisionController.class
                .getMethod("evaluate", DecisionApplicationService.DecisionRequest.class)
                .getAnnotation(PostMapping.class);

        assertEquals("/api/v1", root.value()[0]);
        assertEquals("/decisions:evaluate", action.value()[0]);
        assertEquals("/api/v1/decisions:evaluate",
                Arrays.stream(root.value()).findFirst().orElseThrow()
                        + Arrays.stream(action.value()).findFirst().orElseThrow());
    }
}
