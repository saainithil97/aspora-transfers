package com.aspora.interview.transfers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.DriverManager;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Skips the annotated tests, with a message, when the Postgres from `make up` is not reachable. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresPostgres.Condition.class)
public @interface RequiresPostgres {

    class Condition implements ExecutionCondition {

        private static ConditionEvaluationResult cached;

        @Override
        public synchronized ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (cached == null) {
                cached = probe();
            }
            return cached;
        }

        private static ConditionEvaluationResult probe() {
            String url = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/transfers");
            String user = env("DATABASE_USER", "transfers");
            String password = env("DATABASE_PASSWORD", "transfers");
            DriverManager.setLoginTimeout(3);
            try (var ignored = DriverManager.getConnection(url, user, password)) {
                return ConditionEvaluationResult.enabled("Postgres reachable at " + url);
            } catch (Exception e) {
                String reason = "SKIPPING integration tests: Postgres not reachable at " + url
                        + " (" + e.getMessage().strip() + "). Start it with `make up`.";
                System.err.println(reason);
                return ConditionEvaluationResult.disabled(reason);
            }
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
