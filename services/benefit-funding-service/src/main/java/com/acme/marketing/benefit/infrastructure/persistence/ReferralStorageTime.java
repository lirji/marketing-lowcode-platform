package com.acme.marketing.benefit.infrastructure.persistence;

import java.time.*;
import java.time.temporal.ChronoUnit;

/** DATETIME(6)加纳秒余数精确保存安全时限；不允许截断后的数据库时间替代原始时限。 */
public final class ReferralStorageTime {
    private ReferralStorageTime(){}
    public static LocalDateTime micros(Instant value){return value==null?null:LocalDateTime.ofInstant(value.truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);}
    public static Integer remainder(Instant value){return value==null?null:value.getNano()%1000;}
    public static Instant restore(LocalDateTime micros,Integer remainder) {
        if(micros==null && remainder==null)return null;
        if(micros==null || remainder==null || remainder<0 || remainder>999 || micros.getNano()%1000!=0)
            throw new IllegalStateException("invalid referral stored time");
        return micros.toInstant(ZoneOffset.UTC).plusNanos(remainder);
    }
}
