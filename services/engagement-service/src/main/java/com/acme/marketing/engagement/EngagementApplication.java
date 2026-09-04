package com.acme.marketing.engagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EngagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(EngagementApplication.class, args);
    }
}
