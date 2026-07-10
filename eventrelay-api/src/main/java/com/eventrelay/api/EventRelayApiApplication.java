package com.eventrelay.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.eventrelay")
@EntityScan("com.eventrelay.core.domain")
@EnableJpaRepositories("com.eventrelay.core.repository")
@EnableScheduling
public class EventRelayApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventRelayApiApplication.class, args);
    }
}
