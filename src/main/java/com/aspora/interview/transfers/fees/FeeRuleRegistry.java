package com.aspora.interview.transfers.fees;

import com.aspora.interview.transfers.domain.TransferType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Loads the active fee_rules rows once at startup and maps each rule_type to an implementation.
 * A rule_type this code does not know is a configuration error and stops the service from starting.
 */
@Component
public class FeeRuleRegistry {

    private record Key(TransferType type, String currency) {
    }

    private final Map<Key, FeeRule> rules = new HashMap<>();

    public FeeRuleRegistry(JdbcClient db, ObjectMapper json) {
        db.sql("select transfer_type, currency, rule_type, params::text as params from fee_rules where active")
                .query((rs, i) -> {
                    Key key = new Key(TransferType.valueOf(rs.getString("transfer_type")), rs.getString("currency"));
                    FeeRule rule = build(rs.getString("rule_type"), parse(json, rs.getString("params")));
                    if (rules.putIfAbsent(key, rule) != null) {
                        throw new IllegalStateException("more than one active fee rule for " + key);
                    }
                    return key;
                })
                .list();
    }

    public long feeFor(TransferType type, String currency, long amountMinor) {
        FeeRule rule = rules.get(new Key(type, currency));
        return rule == null ? 0 : rule.feeFor(amountMinor);
    }

    private static FeeRule build(String ruleType, JsonNode params) {
        return switch (ruleType) {
            case "FLAT" -> new FlatFeeRule(params.get("amountMinor").asLong());
            default -> throw new IllegalStateException("unknown fee rule_type: " + ruleType);
        };
    }

    private static JsonNode parse(ObjectMapper json, String params) {
        try {
            return json.readTree(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("fee rule params are not valid JSON: " + params, e);
        }
    }
}
