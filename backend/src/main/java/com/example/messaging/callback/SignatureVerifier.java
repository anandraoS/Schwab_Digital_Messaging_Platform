package com.example.messaging.callback;

import com.example.messaging.config.MessagingProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies {@code X-Signature: t=<epoch seconds>,v1=<hex>}: HMAC-SHA256 over {@code t + "." + rawBody} computed on
 * the raw request bytes (never on re-serialised JSON), compared in constant time, with a timestamp tolerance
 * ({@code messaging.callback.tolerance}, default 5 minutes). Several {@code v1=} values are accepted so a provider
 * can sign with two secrets during rotation. Secrets are dummy values from configuration.
 */
@Component
public class SignatureVerifier {

    public enum Result { VALID, MISSING_HEADER, MALFORMED_HEADER, TIMESTAMP_OUT_OF_TOLERANCE, MISMATCH }

    private final MessagingProperties props;
    private final Clock clock;

    public SignatureVerifier(MessagingProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /** Secret configured for a key id ({@code messaging.callback.secrets.<keyId>}). */
    public Optional<String> secretFor(String keyId) {
        return Optional.ofNullable(keyId == null ? null : props.callback().secrets().get(keyId))
                .filter(s -> !s.isBlank());
    }

    public Result verify(String header, String secret, byte[] rawBody) {
        if (header == null || header.isBlank()) {
            return Result.MISSING_HEADER;
        }
        Long t = null;
        List<String> candidates = new ArrayList<>();
        for (String part : header.split(",")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = p.substring(0, eq);
            String v = p.substring(eq + 1);
            if (k.equals("t")) {
                try {
                    t = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    return Result.MALFORMED_HEADER;
                }
            } else if (k.equals("v1")) {
                candidates.add(v.toLowerCase(Locale.ROOT));
            }
        }
        if (t == null || candidates.isEmpty()) {
            return Result.MALFORMED_HEADER;
        }
        long skew = Math.abs(clock.instant().getEpochSecond() - t);
        if (skew > props.callback().tolerance().toSeconds()) {
            return Result.TIMESTAMP_OUT_OF_TOLERANCE;
        }
        byte[] expected = sign(secret, t, rawBody).getBytes(StandardCharsets.US_ASCII);
        boolean match = false;
        for (String c : candidates) {
            // bitwise OR keeps the loop free of early exits; isEqual is constant-time for equal lengths
            match |= MessageDigest.isEqual(expected, c.getBytes(StandardCharsets.US_ASCII));
        }
        return match ? Result.VALID : Result.MISMATCH;
    }

    /** Lower-case hex HMAC-SHA256 of {@code t + "." + body}. */
    public static String sign(String secret, long epochSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((epochSeconds + ".").getBytes(StandardCharsets.US_ASCII));
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** The full header value a provider would send. */
    public static String header(String secret, long epochSeconds, byte[] body) {
        return "t=" + epochSeconds + ",v1=" + sign(secret, epochSeconds, body);
    }
}
