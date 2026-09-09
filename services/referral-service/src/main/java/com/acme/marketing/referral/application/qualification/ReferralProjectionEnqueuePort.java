package com.acme.marketing.referral.application.qualification;

/**
 * 仅内部持久任务入口，必须加入调用方事务；BOUND与fanout来源均来自已锁定/已读取的永久关系。
 * 实现不对relation/current建立外键，防止fanout→task的隐式父行锁与current→fanout形成锁环。
 * requestedRevision按关系独立递增，不能把不同订单的evidenceVersion直接比较大小。
 */
public interface ReferralProjectionEnqueuePort {
    /** 本方法成功与调用方游标/绑定提交原子，不可异步另开事务或在这里判资格。 */
    void enqueue(Signal signal);

    /** 来源只能是已确认内部记录；证据资源/版本只是触发线索，实际首单仍由可信历史Permit选择。 */
    record Signal(String tenantId,String participantId,String relationId,String resourceId,long desiredVersion,String reason) {
        public Signal {
            text(tenantId);text(participantId);text(relationId);text(reason);
            if(resourceId==null){if(desiredVersion!=0 || !java.util.Set.of("BOUND","MANUAL_REEVALUATION").contains(reason))throw invalid();}
            else {text(resourceId);if(desiredVersion<=0 || java.util.Set.of("BOUND","MANUAL_REEVALUATION").contains(reason))throw invalid();}
        }
        @Override public String toString(){return "ReferralProjectionSignal[redacted]";}
        private static void text(String value){if(value==null || value.isBlank() || value.length()>64)throw invalid();}
        private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid internal projection signal");}
    }
}
