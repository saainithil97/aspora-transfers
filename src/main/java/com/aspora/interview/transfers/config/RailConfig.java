package com.aspora.interview.transfers.config;

import com.aspora.interview.transfers.rail.FakeRailClient;
import com.aspora.interview.transfers.rail.RailClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class RailConfig {

    @Bean
    public RailClient railClient(AppProperties props, JdbcClient db, @Value("${server.port}") int port) {
        return new FakeRailClient(props.rail(), db, "http://localhost:" + port);
    }
}
