package com.acme.marketing.platform.web;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.error.UnauthorizedException;
import com.acme.marketing.platform.error.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    public ProblemDetail domainException(DomainException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception) {
            case NotFoundException ignored -> HttpStatus.NOT_FOUND;
            case UnauthorizedException ignored -> HttpStatus.UNAUTHORIZED;
            case ConflictException ignored -> HttpStatus.CONFLICT;
            case ForbiddenException ignored -> HttpStatus.FORBIDDEN;
            case ValidationException ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            // fail-closed 风控和入口容量保护都表示“稍后重试”；其它上游协议失败保持 502。
            case DependencyUnavailableException dependency -> List.of(
                            "RISK_UNAVAILABLE", "EVENT_OUTBOX_BACKPRESSURE",
                            "AWARD_INTENT_IN_PROGRESS").contains(dependency.code())
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail problem = base(status, exception.code(), exception.getMessage(), exception.retryable(), request);
        if (exception instanceof ValidationException validation) {
            problem.setProperty("violations", validation.violations());
        }
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidRequest(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ValidationException.Violation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationException.Violation(
                        "/" + error.getField().replace('.', '/'), null, "INVALID_FIELD", error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_FAILED",
                "request validation failed", false, request);
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return base(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", exception.getMessage(), false, request);
    }

    private static ProblemDetail base(
            HttpStatus status, String code, String detail, boolean retryable, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? status.getReasonPhrase() : detail);
        problem.setType(URI.create("urn:marketing:problem:" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("traceId", correlationId(request));
        problem.setProperty("retryable", retryable);
        return problem;
    }

    private static String correlationId(HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        if (traceId != null && traceId.matches("[0-9a-fA-F]{16,32}")) return traceId.toLowerCase();
        String requestId = request.getHeader("X-Request-Id");
        if (requestId != null && requestId.matches("[A-Za-z0-9._:-]{1,128}")) return requestId;
        return UUID.randomUUID().toString();
    }
}
