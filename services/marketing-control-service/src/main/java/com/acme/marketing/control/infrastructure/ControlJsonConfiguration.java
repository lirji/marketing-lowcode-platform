package com.acme.marketing.control.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.core.StreamReadFeature;

/** 控制面JSON边界：重复role/ruleId/facts不能在绑定前静默覆盖。 */
@Configuration
public class ControlJsonConfiguration {
    /** 与编译器相同的严格重复字段策略；合法旧请求不受影响。 */
    @Bean
    public JsonMapperBuilderCustomizer controlStrictJson() {
        return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }
}
