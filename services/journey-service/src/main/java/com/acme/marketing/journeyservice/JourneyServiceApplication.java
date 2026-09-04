package com.acme.marketing.journeyservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JourneyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(JourneyServiceApplication.class, args);
    }
}
