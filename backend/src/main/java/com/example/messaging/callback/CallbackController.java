package com.example.messaging.callback;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider webhook. No bearer token: {@code permitAll} at the Spring Security layer and authenticated by the HMAC
 * signature inside {@link CallbackService}. The body is read as raw bytes so the signature is computed over exactly
 * what the provider sent.
 */
@RestController
@RequestMapping("/api/v1/callbacks")
@Tag(name = "Callbacks")
public class CallbackController {

    /** Response body ({@code CallbackAck} in the OpenAPI contract). */
    public record CallbackAck(String result, String eventId) {}

    private final CallbackService callbacks;

    public CallbackController(CallbackService callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping(value = "/{providerId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "HMAC-authenticated provider webhook (delivery receipts)")
    public ResponseEntity<CallbackAck> receive(@PathVariable String providerId,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "X-Provider-Key-Id", required = false) String keyId,
            @RequestHeader(value = "X-Event-Id", required = false) String eventId,
            @RequestBody byte[] rawBody) {
        CallbackService.Response r = callbacks.handle(providerId, keyId, signature, eventId, rawBody);
        return ResponseEntity.status(r.status()).body(new CallbackAck(r.result(), r.eventId()));
    }
}
