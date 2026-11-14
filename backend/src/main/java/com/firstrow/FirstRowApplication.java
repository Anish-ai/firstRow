package com.firstrow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FirstRowApplication {

    public static void main(String[] args) {
        SpringApplication.run(FirstRowApplication.class, args);
    }

    /** Injected instead of calling Instant.now() directly, so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
