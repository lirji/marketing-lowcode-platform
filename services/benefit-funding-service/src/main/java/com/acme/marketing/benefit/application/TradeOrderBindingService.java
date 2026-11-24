package com.acme.marketing.benefit.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.benefit.application.TradeOrderBindingRepository.ApplicationKey;
import com.acme.marketing.benefit.application.TradeOrderBindingRepository.Binding;
import com.acme.marketing.benefit.application.TradeOrderBindingRepository.SourceOrder;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 将原始签名报价指定的原生订单与资金申请固定关联，供后续对账引用。 */
@Service
@ConditionalOnProperty(name = "marketing.trade-order-bindings.enabled", havingValue = "true")
public class TradeOrderBindingService {
    static final String PERMISSION = "trade-order-binding:write";
    private static final String OFFER_MISMATCH = "TRADE_ORDER_BINDING_OFFER_MISMATCH";
    private static final Pattern APPLICATION_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern NATIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final int MAX_SUBJECT_LENGTH = 255;

    private final BenefitFundingService funding;
    private final TradeOrderBindingRepository repository;
    private final Clock clock;
    private final Set<SourceAuthority> authorities;

    /** 将允许建立关联的业务租户、机器主体和原生租户当作整体配置。 */
    public TradeOrderBindingService(BenefitFundingService funding, TradeOrderBindingRepository repository, Clock clock,
            @Value("${marketing.trade-order-bindings.sources:}") String sources) {
        this.funding = funding;
        this.repository = repository;
        this.clock = clock;
        this.authorities = parseAuthorities(sources);
    }

    /** 先验证身份和原始报价，再在同一资金申请行锁内判断重放或写入新绑定。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BindingView bind(String applicationId, String verifiedSubject, BindRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        long sourceTenantId = nativeId(request.sourceTenantId(), "sourceTenantId");
        if (verifiedSubject == null || !scope.actorId().equals(verifiedSubject)
                || !scope.permissions().contains(PERMISSION)
                || !authorities.contains(new SourceAuthority(scope.tenantId(), verifiedSubject, sourceTenantId))) {
            throw forbidden();
        }
        return bindAuthorized(applicationId, scope, sourceTenantId, request);
    }

    /** 应用令牌的业务范围在专用入口解析，继续使用同一把申请行锁及首次绑定审计。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BindingView bindMachine(String applicationId, TradeOrderBindingMachineAuthority.AuthorizedMachine machine,
            BindRequest request) {
        long sourceTenantId = nativeId(request.sourceTenantId(), "sourceTenantId");
        if (sourceTenantId != machine.sourceTenantId()) {
            throw forbidden();
        }
        return bindAuthorized(applicationId, machine.scope(), sourceTenantId, request);
    }

    private BindingView bindAuthorized(String applicationId, TenantScope scope, long sourceTenantId, BindRequest request) {
        if (!APPLICATION_ID.matcher(applicationId).matches()) {
            throw new IllegalArgumentException("applicationId 格式无效");
        }
        long sourceOrderId = nativeId(request.sourceOrderId(), "sourceOrderId");
        var evidence = funding.verifyOriginalOfferForUpdate(scope, applicationId, request.offerToken(), OFFER_MISMATCH);
        String order = Long.toString(sourceOrderId);
        // 普通预留支持未签名的订单号，绑定必须由原始报价明确声明原生编号。
        if (!order.equals(evidence.claims().orderId()) || !order.equals(evidence.row().orderId())) {
            throw new ConflictException("TRADE_ORDER_BINDING_ORDER_MISMATCH", "原始签名报价必须声明该交易订单号");
        }
        String tenantId = scope.tenantId().value();
        Binding existing = repository.findBinding(new ApplicationKey(tenantId, applicationId));
        if (existing != null) {
            if (existing.sourceTenantId() != sourceTenantId || existing.sourceOrderId() != sourceOrderId) {
                throw new ConflictException("TRADE_ORDER_BINDING_CONFLICT", "资金申请已绑定其他交易订单");
            }
            return BindingView.from(existing);
        }
        var receipt = repository.findReceipt(new SourceOrder(sourceTenantId, sourceOrderId));
        if (receipt == null) {
            throw new ConflictException("TRADE_ORDER_BINDING_RECEIPT_MISSING", "该交易订单的原生回执尚未入箱");
        }
        if (!receipt.currency().equals(evidence.claims().offerLines().getFirst().currency())) {
            throw new ConflictException("TRADE_ORDER_BINDING_CURRENCY_MISMATCH", "原始报价与交易回执币种不一致");
        }
        Binding binding = new Binding(tenantId, applicationId, sourceTenantId, sourceOrderId,
                receipt.eventId(), receipt.evidenceVersion(), scope.actorId(), format(clock.instant()));
        if (repository.insertBinding(binding) != 1) {
            throw new IllegalStateException("交易订单绑定未写入");
        }
        return BindingView.from(binding);
    }

    static ForbiddenException forbidden() {
        return new ForbiddenException("TRADE_ORDER_BINDING_FORBIDDEN", "当前机器身份未获该交易租户的绑定权限");
    }

    private static Set<SourceAuthority> parseAuthorities(String sources) {
        if (sources.isBlank()) {
            return Set.of();
        }
        Set<SourceAuthority> entries = new HashSet<>();
        for (String source : sources.split(",", -1)) {
            String[] fields = source.strip().split("\\|", -1);
            if (fields.length != 3 || fields[1].isBlank() || fields[1].length() > MAX_SUBJECT_LENGTH
                    || !fields[1].equals(fields[1].strip()) || "*".equals(fields[1])) {
                throw new IllegalArgumentException("交易订单绑定来源必须为营销租户|机器JWT主体|原生交易租户");
            }
            entries.add(new SourceAuthority(new TenantId(fields[0]), fields[1], nativeId(fields[2], "sourceTenantId")));
        }
        return Set.copyOf(entries);
    }

    static long nativeId(String value, String name) {
        if (value != null && NATIVE_ID.matcher(value).matches()) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException overflow) {
                // 字段使用字符串传输，以完整保留正 64 位原生编号。
            }
        }
        throw new IllegalArgumentException(name + " 必须为正的 64 位十进制编号");
    }

    private record SourceAuthority(TenantId marketingTenant, String jwtSubject, long sourceTenantId) { }

    /** 原始报价只参与验签，诊断信息不输出凭证。 */
    public record BindRequest(String sourceTenantId, String sourceOrderId, String offerToken) {
        @Override public String toString() { return "BindRequest[offerToken=REDACTED]"; }
    }

    /** 审计引用固定为首次绑定采用的回执，原生数字标识保留字符串精度。 */
    public record BindingView(String applicationId, String sourceTenantId, String sourceOrderId,
            String receiptEventId, String evidenceVersion, String boundAt) {
        private static BindingView from(Binding binding) {
            return new BindingView(binding.applicationId(), Long.toString(binding.sourceTenantId()),
                    Long.toString(binding.sourceOrderId()), binding.receiptEventId(),
                    Long.toString(binding.evidenceVersion()), binding.boundAt());
        }
    }
}
