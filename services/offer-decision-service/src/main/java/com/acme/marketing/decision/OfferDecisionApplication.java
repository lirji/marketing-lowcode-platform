package com.acme.marketing.decision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OfferDecisionApplication {
    public static void main(String[] args) {
        SpringApplication.run(OfferDecisionApplication.class, args);
    }
}
