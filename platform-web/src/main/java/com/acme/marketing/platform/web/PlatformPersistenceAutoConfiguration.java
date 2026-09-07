package com.acme.marketing.platform.web;

import com.acme.marketing.platform.web.persistence.IdempotencyCommandMapper;
import java.time.Clock;
import java.time.Duration;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** 仅在 MyBatis 可用的数据库服务中装配持久化幂等基础设施。 */
@AutoConfiguration
@ConditionalOnClass(name = "org.mybatis.spring.SqlSessionTemplate")
@AutoConfigureAfter(name = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration"
})
public class PlatformPersistenceAutoConfiguration {
    /** 从 MyBatis 会话创建平台 Mapper，使自动配置包之外的接口也能被可靠注册。 */
    @Bean
    @ConditionalOnBean(SqlSessionTemplate.class)
    public IdempotencyCommandMapper idempotencyCommandMapper(SqlSessionTemplate session) {
        // 平台 Mapper 不在各服务的自动扫描根包内，因此在共享自动配置中显式注册。
        if (!session.getConfiguration().hasMapper(IdempotencyCommandMapper.class)) {
            session.getConfiguration().addMapper(IdempotencyCommandMapper.class);
        }
        return session.getMapper(IdempotencyCommandMapper.class);
    }

    /** 为使用关系型数据库的领域服务提供跨副本、可重启恢复的 API 幂等执行器。 */
    @Bean
    @ConditionalOnBean({IdempotencyCommandMapper.class, PlatformTransactionManager.class})
    public PersistentIdempotentCommandExecutor persistentIdempotentCommandExecutor(
            IdempotencyCommandMapper commands, ObjectMapper mapper,
            Clock clock, PlatformTransactionManager transactionManager) {
        return new PersistentIdempotentCommandExecutor(commands, mapper, clock, transactionManager,
                Duration.ofDays(7));
    }
}
