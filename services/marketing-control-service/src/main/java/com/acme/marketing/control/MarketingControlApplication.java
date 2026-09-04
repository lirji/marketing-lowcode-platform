package com.acme.marketing.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MarketingControlApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketingControlApplication.class, args);
    }
}
