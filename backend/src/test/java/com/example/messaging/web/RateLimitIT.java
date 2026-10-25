package com.example.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Own context: token bucket of 1 request/second with a burst of 3 per client. */
@TestPropertySource(properties = {"messaging.api.rate-per-sec=1", "messaging.api.burst=3"})
class RateLimitIT extends AbstractIntegrationTest {

    @Test
    void burstThenTooManyRequestsWithHeadersThenRefill() throws Exception {
        String clientA = bearer("rate-client-a", RETAIL, "SENDER");
        String clientB = bearer("rate-client-b", RETAIL, "SENDER");

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/me").header("Authorization", clientA))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(2 - i)))
                    .andExpect(header().exists("X-RateLimit-Reset"));
        }
        MvcResult denied = mvc.perform(get("/api/v1/me").header("Authorization", clientA))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code", is("RATE_LIMITED")))
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn();
        assertThat(Long.parseLong(denied.getResponse().getHeader("Retry-After"))).isBetween(1L, 2L);

        // limits are per client: another client is unaffected
        mvc.perform(get("/api/v1/me").header("Authorization", clientB)).andExpect(status().isOk());

        // the bucket refills at 1 token/second
        Thread.sleep(1200);
        mvc.perform(get("/api/v1/me").header("Authorization", clientA)).andExpect(status().isOk());

        // rejections are counted; unauthenticated traffic and health checks are not rate limited as a client
        String prom = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(prom.lines().filter(l -> l.startsWith("messaging_ratelimit_rejected_total"))
                .anyMatch(l -> l.contains("scope=\"api\""))).isTrue();
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }
}
