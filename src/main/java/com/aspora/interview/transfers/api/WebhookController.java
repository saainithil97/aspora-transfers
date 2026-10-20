package com.aspora.interview.transfers.api;

import com.aspora.interview.transfers.config.AppProperties;
import com.aspora.interview.transfers.domain.ApiException;
import com.aspora.interview.transfers.domain.ErrorCode;
import com.aspora.interview.transfers.rail.RailWebhookHandler;
import com.aspora.interview.transfers.rail.WebhookSignature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    public record RailEvent(String railRef, String event) {
    }

    private final RailWebhookHandler handler;
    private final ObjectMapper json;
    private final String secret;

    public WebhookController(RailWebhookHandler handler, ObjectMapper json, AppProperties props) {
        this.handler = handler;
        this.json = json;
        this.secret = props.rail().webhookSecret();
    }

    /** The body is taken raw because the signature covers the exact bytes the rail sent. */
    @PostMapping("/internal/rail/webhook")
    public ResponseEntity<Void> receive(@RequestBody String rawBody,
            @RequestHeader(value = "X-Rail-Signature", required = false) String signature) {
        if (!WebhookSignature.matches(secret, rawBody, signature)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "bad or missing X-Rail-Signature");
        }
        RailEvent event;
        try {
            event = json.readValue(rawBody, RailEvent.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.VALIDATION, "body must be JSON {railRef, event}");
        }
        handler.handle(event.railRef(), event.event(), rawBody);
        return ResponseEntity.noContent().build();
    }
}
