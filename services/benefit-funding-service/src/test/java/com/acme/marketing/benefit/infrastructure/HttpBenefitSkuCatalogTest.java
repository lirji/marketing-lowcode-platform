package com.acme.marketing.benefit.infrastructure;

import com.acme.marketing.benefit.application.BenefitSkuCatalog.SkuStatus;
import com.acme.marketing.platform.error.UnauthorizedException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 权益目录 M2M 身份边界测试。 */
class HttpBenefitSkuCatalogTest {

    @Test
    void blankMachineTokenSendsNoAuthorizationAndMapsUnauthorizedProblem() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/catalog/skus", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();

        try {
            HttpBenefitSkuCatalog catalog = new HttpBenefitSkuCatalog(
                    new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "",
                    Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThatThrownBy(() -> catalog.list("dev-tenant", SkuStatus.ACTIVE))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            error -> assertThat(error.code()).isEqualTo("BENEFIT_CATALOG_AUTH_REQUIRED"));
            assertThat(authorization).hasNullValue();
        } finally {
            server.stop(0);
        }
    }
}
