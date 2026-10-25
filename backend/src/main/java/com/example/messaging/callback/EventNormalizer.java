package com.example.messaging.callback;

import com.example.messaging.callback.ProviderPayloadMapper.NormalizedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Dispatches a raw callback body to the mapper of its provider and returns the canonical event. Pure and
 * side-effect free. A provider gets a mapper by registering a {@link ProviderPayloadMapper} bean.
 */
@Component
public class EventNormalizer {

    private final Map<String, ProviderPayloadMapper> mappers;
    private final ObjectMapper json;

    public EventNormalizer(List<ProviderPayloadMapper> mappers, ObjectMapper json) {
        Map<String, ProviderPayloadMapper> m = new HashMap<>();
        for (ProviderPayloadMapper pm : mappers) {
            if (m.putIfAbsent(pm.providerId(), pm) != null) {
                throw new IllegalStateException("Two payload mappers for provider " + pm.providerId());
            }
        }
        this.mappers = Map.copyOf(m);
        this.json = json;
    }

    public Optional<ProviderPayloadMapper> mapperFor(String providerId) {
        return Optional.ofNullable(mappers.get(providerId));
    }

    /**
     * @throws MalformedCallbackException for non-JSON bodies, non-object roots, unknown vendor statuses or missing
     *                                    identifiers (the exception text never contains payload content)
     */
    public NormalizedEvent normalize(String providerId, byte[] rawBody, String headerEventId, Instant receivedAt) {
        ProviderPayloadMapper mapper = mappers.get(providerId);
        if (mapper == null) {
            throw new MalformedCallbackException("NO_MAPPER_FOR_PROVIDER");
        }
        JsonNode root;
        try {
            root = json.readTree(rawBody);
        } catch (JsonProcessingException e) {
            throw new MalformedCallbackException("NOT_JSON");
        } catch (IOException e) {
            throw new MalformedCallbackException("UNREADABLE");
        }
        if (root == null || !root.isObject()) {
            throw new MalformedCallbackException("NOT_A_JSON_OBJECT");
        }
        return mapper.map(root, headerEventId, receivedAt);
    }
}
