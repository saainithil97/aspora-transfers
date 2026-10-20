package com.aspora.interview.transfers.config;

import com.aspora.interview.transfers.rail.FakeRailClient;
import com.aspora.interview.transfers.rail.RailClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RailConfig {

    @Bean
    public RailClient railClient(AppProperties props, @Value("${server.port}") int port) {
        return new FakeRailClient(props.rail(), "http://localhost:" + port);
    }
}
