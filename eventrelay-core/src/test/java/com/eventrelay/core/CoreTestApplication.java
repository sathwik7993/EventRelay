package com.eventrelay.core;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Minimal Spring Boot context for exercising core services against a real database. */
@SpringBootApplication(scanBasePackages = "com.eventrelay.core")
@EntityScan("com.eventrelay.core.domain")
@EnableJpaRepositories("com.eventrelay.core.repository")
public class CoreTestApplication {
}
