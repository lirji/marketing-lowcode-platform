package com.acme.marketing.jobs.journey;

/** 系统依赖失败由作业恢复机制处理，不能转换成业务死信后确认消费。 */
public final class JourneyInfrastructureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 保留根因供运维排查；顶层信息不携带连接串或凭据。 */
    public JourneyInfrastructureException(Throwable cause) {
        super("JOURNEY_RUNTIME_STORE_UNAVAILABLE", cause);
    }
}
