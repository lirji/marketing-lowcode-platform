package com.acme.marketing.engagement.interfaces;

import com.acme.marketing.engagement.application.EngagementService;
import com.acme.marketing.engagement.infrastructure.ProviderCallbackAuthenticator;
import com.acme.marketing.provider.ProviderCallback;
import com.acme.marketing.platform.web.PersistentIdempotentCommandExecutor;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class EngagementController {
    private final EngagementService service;
    private final ProviderCallbackAuthenticator callbackAuthenticator;
    private final PersistentIdempotentCommandExecutor commands;
    public EngagementController(EngagementService service, ProviderCallbackAuthenticator callbackAuthenticator,
            PersistentIdempotentCommandExecutor commands) {
        this.service = service;
        this.callbackAuthenticator = callbackAuthenticator;
        this.commands = commands;
    }

    @PutMapping("/consents")
    public EngagementService.ConsentView consent(@RequestBody EngagementService.ConsentRequest request) {
        return service.setConsent(request);
    }

    @PutMapping("/consents/suppressions")
    public EngagementService.SuppressionView suppress(@RequestBody EngagementService.SuppressionRequest request) {
        return service.suppress(request);
    }

    @PutMapping("/contacts/frequency-policies")
    public EngagementService.FrequencyPolicy policy(@RequestBody EngagementService.FrequencyPolicy request) {
        return service.setPolicy(request);
    }

    @PostMapping("/templates") @ResponseStatus(HttpStatus.CREATED)
    public EngagementService.TemplateView template(@RequestHeader("Idempotency-Key") String key,
            @RequestBody EngagementService.TemplateRequest request) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "engagement.template.create",
                key, request, EngagementService.TemplateView.class, () -> service.createTemplate(request));
    }

    @GetMapping("/templates")
    public List<EngagementService.TemplateView> templates() {
        return service.templates();
    }

    @PostMapping("/contacts") @ResponseStatus(HttpStatus.ACCEPTED)
    public EngagementService.ContactView send(@RequestBody EngagementService.SendRequest request) {
        return service.send(request);
    }

    @GetMapping("/contacts")
    public List<EngagementService.ContactView> contacts(
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "50") int limit) {
        return service.contacts(state, limit);
    }

    @GetMapping("/contacts/{contactKey}")
    public EngagementService.ContactView contact(@PathVariable String contactKey) {
        return service.contact(contactKey);
    }

    @PostMapping("/providers/callbacks")
    public EngagementService.ContactView callback(@RequestBody byte[] body,
            @RequestHeader(value = "X-Marketing-Provider-Request-Id", required = false) String providerRequestId,
            @RequestHeader(value = "X-Marketing-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Marketing-Signature", required = false) String signature) {
        ProviderCallback callback = callbackAuthenticator.authenticate(body, providerRequestId, timestamp, signature);
        return service.callback(callback);
    }
}
