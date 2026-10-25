package com.example.messaging;

import com.example.messaging.config.MessagingProperties;
import java.time.Duration;
import java.util.Map;

/** Builds {@link MessagingProperties} for plain unit tests (defaults normally come from Spring binding). */
public final class TestProps {

    private TestProps() {}

    public static MessagingProperties defaults() {
        return new MessagingProperties(
                new MessagingProperties.Dispatch(8, 16, 5, 1000, 60000, 5, Duration.ofMinutes(15),
                        Duration.ofMinutes(5), Duration.ofMinutes(2)),
                new MessagingProperties.Backpressure(5000),
                new MessagingProperties.Bus("memory"),
                new MessagingProperties.Coordination("memory", "msg:", Duration.ofSeconds(30)),
                Map.of(),
                new MessagingProperties.Callback(Duration.ofMinutes(5), Map.of()),
                new MessagingProperties.Api(50, 100, 262144),
                new MessagingProperties.Bulk(4, 500, 100, 100000, "target/bulk-uploads"),
                new MessagingProperties.Retention(90, 365, 30),
                new MessagingProperties.Security(false, "test-issuer", Duration.ofHours(1), false, false),
                new MessagingProperties.Templates(50, 2000, 10000, 200000, 10, 65, 240, 255),
                new MessagingProperties.Idempotency(Duration.ofSeconds(30), Duration.ofHours(24)));
    }
}
