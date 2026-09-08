package com.acme.marketing.referral;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** 裂变服务独立进程；当前只装配内部参与服务，没有公开写入口或后台投递。 */
@SpringBootApplication
public class ReferralApplication {
    /** 启动时必须显式提供数据库与认证配置，不隐式连接共享开发库。 */
    public static void main(String[] args) { SpringApplication.run(ReferralApplication.class,args); }
}
