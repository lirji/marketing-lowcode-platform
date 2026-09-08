package com.acme.marketing.referral.domain.qualification;
import java.util.Objects;

/**
 * 只计算已裁决资格标志的人数变更，不判断资格、不认证来源、不签发奖励。
 * 调用方仍须在同participant锁、真实证据水位与有效权威许可下原子保存资格/人数/Outbox。
 */
public final class ReferralProgressTransition {
    private ReferralProgressTransition() { }
    /** 永久关系归属由首绑固定，不能给同一资格换活动参与者来重复累计。 */
    public record Key(String tenantId,String participantId,String relationId) {
        /** 只接受完整业务资源身份，不使用主体明文或拼接字符串做键。 */
        public Key { text(tenantId);text(participantId);text(relationId); }
    }
    /** 当前标志和曾经达标标志分离，退款撤销不清除历史唯一达标记忆。 */
    public record Flags(Key key,boolean counted,boolean everQualified) {
        /** 当前有效必然曾经有效，破坏该约束的历史不能静默修复。 */
        public Flags { Objects.requireNonNull(key);if(counted && !everQualified)throw invalid(); }
    }
    /** 同参与者锁下读取的持久人数水位；此处不假装读取或锁定数据库。 */
    public record Progress(String tenantId,String participantId,long validCount,long everQualifiedCount,long revision) {
        /** 人数非负且当前不能大于历史唯一人数，修订始终为正。 */
        public Progress { text(tenantId);text(participantId);if(validCount<0 || everQualifiedCount<validCount || revision<=0)throw invalid(); }
    }
    /** 不含任何奖励候选；是否发PROGRESS_CHANGED取决于实际人数变化。 */
    public record Mutation(Flags next,Progress progress,long validDelta,long everDelta) {
        /** 所有构造结果都应由apply返回，持久资格/进度仍需同事务CAS保护。 */
        public Mutation { Objects.requireNonNull(next);Objects.requireNonNull(progress); }
        /** 证据水位变化但人数不变时只更新资格，不伪造人数变化事件。 */
        public boolean changesProgress(){return validDelta!=0 || everDelta!=0;}
    }
    /**
     * nextCounted必须来自完整权威评估，不允许把客户端bool当作准入；本函数只维护计数不变量。
     * 重新达标只恢复当前人数，everQualified不会第二次增加，重复同状态无副作用。
     */
    public static Mutation apply(Key key,Flags previous,Progress progress,boolean nextCounted) {
        Objects.requireNonNull(key);Objects.requireNonNull(progress);
        if(!key.tenantId().equals(progress.tenantId()) || !key.participantId().equals(progress.participantId()) || (previous!=null && !key.equals(previous.key())))throw invalid();
        boolean wasCounted=previous!=null && previous.counted(),wasEver=previous!=null && previous.everQualified();
        if((wasCounted && progress.validCount()==0) || (wasEver && progress.everQualifiedCount()==0))throw invalid();
        long validDelta=(nextCounted?1:0)-(wasCounted?1:0),everDelta=nextCounted&&!wasEver?1:0;
        long valid=Math.addExact(progress.validCount(),validDelta),ever=Math.addExact(progress.everQualifiedCount(),everDelta);
        long revision=validDelta==0 && everDelta==0?progress.revision():Math.addExact(progress.revision(),1);
        return new Mutation(new Flags(key,nextCounted,wasEver||nextCounted),new Progress(key.tenantId(),key.participantId(),valid,ever,revision),validDelta,everDelta);
    }
    private static void text(String value){if(value==null || value.isBlank())throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("qualification count scope or invariant inconsistent");}
}
