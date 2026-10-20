package com.aspora.interview.transfers;

import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class TransfersApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(TransfersApplication.class);
        if (List.of(args).contains("--migrate-only")) {
            // Boot the context far enough for Flyway to run, then leave.
            app.setWebApplicationType(WebApplicationType.NONE);
            app.run(args).close();
            return;
        }
        app.run(args);
    }
}
