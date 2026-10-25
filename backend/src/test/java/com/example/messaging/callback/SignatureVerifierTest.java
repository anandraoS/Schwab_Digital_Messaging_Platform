package com.example.messaging.callback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.messaging.TestProps;
import com.example.messaging.callback.SignatureVerifier.Result;
import com.example.messaging.config.MessagingProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    private static final String SECRET = "local-dev-secret-not-for-production";
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private final SignatureVerifier verifier = verifier(Duration.ofMinutes(5));

    private static SignatureVerifier verifier(Duration tolerance) {
        MessagingProperties d = TestProps.defaults();
        MessagingProperties props = new MessagingProperties(d.dispatch(), d.backpressure(), d.bus(), d.coordination(),
                d.providers(), new MessagingProperties.Callback(tolerance, Map.of("k1", SECRET, "blank", " ")), d.api(),
                d.bulk(), d.retention(), d.security(), d.templates(), d.idempotency());
        return new SignatureVerifier(props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String header(long t, String body) {
        return SignatureVerifier.header(SECRET, t, bytes(body));
    }

    @Test
    void signingMatchesAnIndependentlyComputedHmacOverTimestampDotRawBody() {
        // value computed with Python's hmac module: HMAC-SHA256(secret, "1700000000." + body)
        assertThat(SignatureVerifier.sign(SECRET, 1_700_000_000L, bytes("{\"a\":1}")))
                .isEqualTo("af3a2f7afdeea910e4b5cdacfa0a2b65154bac36aebdf165231dbc57e60d8f24");
        assertThat(SignatureVerifier.sign("Jefe", 1L, bytes("what do ya want for nothing?")))
                .isEqualTo("a9049465ce89cfb8e9aac62333abda66f80e3403f51f57f6bae3c3a4485eca38");
        assertThat(SignatureVerifier.header(SECRET, 1_700_000_000L, bytes("{\"a\":1}")))
                .isEqualTo("t=1700000000,v1=af3a2f7afdeea910e4b5cdacfa0a2b65154bac36aebdf165231dbc57e60d8f24");
    }

    @Test
    void aCorrectSignatureOverTheRawBytesVerifies() {
        String body = "{\"event\":\"delivered\"}";
        assertThat(verifier.verify(header(NOW.getEpochSecond(), body), SECRET, bytes(body))).isEqualTo(Result.VALID);
        // header tolerates spaces, extra parts and upper-case hex
        long t = NOW.getEpochSecond();
        String sig = SignatureVerifier.sign(SECRET, t, bytes(body)).toUpperCase();
        assertThat(verifier.verify("t=" + t + " , v0=ignored , v1=" + sig, SECRET, bytes(body))).isEqualTo(Result.VALID);
    }

    @Test
    void anyChangeToBodySecretOrTimestampBreaksTheSignature() {
        String body = "{\"event\":\"delivered\"}";
        long t = NOW.getEpochSecond();
        String good = header(t, body);
        assertThat(verifier.verify(good, SECRET, bytes(body + " "))).isEqualTo(Result.MISMATCH);
        assertThat(verifier.verify(good, SECRET, bytes(body.replace("delivered", "bounced")))).isEqualTo(Result.MISMATCH);
        assertThat(verifier.verify(good, "another-secret", bytes(body))).isEqualTo(Result.MISMATCH);
        // the timestamp is part of the signed data: moving it invalidates the signature
        String sig = SignatureVerifier.sign(SECRET, t, bytes(body));
        assertThat(verifier.verify("t=" + (t + 1) + ",v1=" + sig, SECRET, bytes(body))).isEqualTo(Result.MISMATCH);
        // same bytes re-serialised differently are a different message
        assertThat(verifier.verify(good, SECRET, bytes("{\"event\": \"delivered\"}"))).isEqualTo(Result.MISMATCH);
    }

    @Test
    void theToleranceIsSymmetricAndInclusive() {
        String body = "{}";
        long now = NOW.getEpochSecond();
        assertThat(verifier.verify(header(now - 300, body), SECRET, bytes(body))).isEqualTo(Result.VALID);
        assertThat(verifier.verify(header(now + 300, body), SECRET, bytes(body))).isEqualTo(Result.VALID);
        assertThat(verifier.verify(header(now - 301, body), SECRET, bytes(body)))
                .isEqualTo(Result.TIMESTAMP_OUT_OF_TOLERANCE);
        assertThat(verifier.verify(header(now + 301, body), SECRET, bytes(body)))
                .isEqualTo(Result.TIMESTAMP_OUT_OF_TOLERANCE);
        // a stricter configured tolerance is honoured
        SignatureVerifier strict = verifier(Duration.ofSeconds(30));
        assertThat(strict.verify(header(now - 31, body), SECRET, bytes(body))).isEqualTo(Result.TIMESTAMP_OUT_OF_TOLERANCE);
        assertThat(strict.verify(header(now - 30, body), SECRET, bytes(body))).isEqualTo(Result.VALID);
    }

    @Test
    void severalCandidatesSupportKeyRotationAndOneMatchSuffices() {
        String body = "{}";
        long t = NOW.getEpochSecond();
        String old = SignatureVerifier.sign("retired-secret", t, bytes(body));
        String current = SignatureVerifier.sign(SECRET, t, bytes(body));
        assertThat(verifier.verify("t=" + t + ",v1=" + old + ",v1=" + current, SECRET, bytes(body))).isEqualTo(Result.VALID);
        assertThat(verifier.verify("t=" + t + ",v1=" + current + ",v1=" + old, SECRET, bytes(body))).isEqualTo(Result.VALID);
        assertThat(verifier.verify("t=" + t + ",v1=" + old, SECRET, bytes(body))).isEqualTo(Result.MISMATCH);
    }

    @Test
    void missingOrMalformedHeadersAreRejectedWithoutThrowing() {
        byte[] body = bytes("{}");
        assertThat(verifier.verify(null, SECRET, body)).isEqualTo(Result.MISSING_HEADER);
        assertThat(verifier.verify("", SECRET, body)).isEqualTo(Result.MISSING_HEADER);
        assertThat(verifier.verify("   ", SECRET, body)).isEqualTo(Result.MISSING_HEADER);
        assertThat(verifier.verify("garbage", SECRET, body)).isEqualTo(Result.MALFORMED_HEADER);
        assertThat(verifier.verify("t=abc,v1=00", SECRET, body)).isEqualTo(Result.MALFORMED_HEADER);
        assertThat(verifier.verify("t=" + NOW.getEpochSecond(), SECRET, body)).isEqualTo(Result.MALFORMED_HEADER);
        assertThat(verifier.verify("v1=" + "0".repeat(64), SECRET, body)).isEqualTo(Result.MALFORMED_HEADER);
        assertThat(verifier.verify("t=,v1=", SECRET, body)).isEqualTo(Result.MALFORMED_HEADER);
        assertThat(verifier.verify("t=99999999999999999999,v1=00", SECRET, body)).isEqualTo(Result.MALFORMED_HEADER);
        // candidates of the wrong length or alphabet simply do not match
        assertThat(verifier.verify("t=" + NOW.getEpochSecond() + ",v1=zz", SECRET, body)).isEqualTo(Result.MISMATCH);
        assertThat(verifier.verify("t=" + NOW.getEpochSecond() + ",v1=" + "0".repeat(1000), SECRET, body))
                .isEqualTo(Result.MISMATCH);
    }

    @Test
    void secretsComeFromConfigurationAndBlankOnesCountAsMissing() {
        assertThat(verifier.secretFor("k1")).contains(SECRET);
        assertThat(verifier.secretFor("blank")).isEmpty();
        assertThat(verifier.secretFor("unknown")).isEmpty();
        assertThat(verifier.secretFor(null)).isEmpty();
    }
}
