package com.acme.marketing.benefit;

import static com.acme.marketing.benefit.application.AwardDispatchModeRouter.DeliveryMode.CENTER;
import static com.acme.marketing.benefit.application.AwardDispatchModeRouter.DeliveryMode.LEGACY;
import static com.acme.marketing.benefit.application.AwardDispatchModeRouter.DeliveryMode.SHADOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acme.marketing.benefit.application.AwardDispatchModeRouter;
import org.junit.jupiter.api.Test;

class AwardDispatchModeRouterTest {
    @Test
    void centerRequiresAnExplicitTenantOverride() {
        AwardDispatchModeRouter router = new AwardDispatchModeRouter("SHADOW",
                "tenant-center=CENTER,tenant-legacy=LEGACY");

        assertEquals(SHADOW, router.modeFor("unconfigured"));
        assertEquals(CENTER, router.modeFor("tenant-center"));
        assertEquals(LEGACY, router.modeFor("tenant-legacy"));
        assertThrows(IllegalArgumentException.class, () -> new AwardDispatchModeRouter("CENTER", ""));
    }
}
