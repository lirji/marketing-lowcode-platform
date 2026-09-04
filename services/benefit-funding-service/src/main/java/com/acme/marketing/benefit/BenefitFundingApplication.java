package com.acme.marketing.benefit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BenefitFundingApplication {
    public static void main(String[] args) {
        SpringApplication.run(BenefitFundingApplication.class, args);
    }
}
