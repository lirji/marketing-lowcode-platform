package com.acme.marketing.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void problemUsesCurrentTraceId() {
        MDC.put("traceId", "0123456789ABCDEF0123456789ABCDEF");
        var problem = handler.invalidArgument(new IllegalArgumentException("invalid"), request());
        assertEquals("0123456789abcdef0123456789abcdef", problem.getProperties().get("traceId"));
    }

    @Test
    void safeRequestIdIsFallbackAndUntrustedValueIsNotReflected() {
        MockHttpServletRequest safe = request();
        safe.addHeader("X-Request-Id", "checkout:request-42");
        assertEquals("checkout:request-42", handler.invalidArgument(
                new IllegalArgumentException("invalid"), safe).getProperties().get("traceId"));

        MockHttpServletRequest unsafe = request();
        unsafe.addHeader("X-Request-Id", "<script>alert(1)</script>");
        String generated = String.valueOf(handler.invalidArgument(
                new IllegalArgumentException("invalid"), unsafe).getProperties().get("traceId"));
        org.junit.jupiter.api.Assertions.assertNotEquals("<script>alert(1)</script>", generated);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
        request.setRequestURI("/api/v1/test");
        return request;
    }
}
