package com.example.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * These tests pin the HTTP contract that the Helm chart, the ServiceScrapes and
 * the pipeline's verify stage all depend on. If any of them fails, something
 * downstream in the platform is about to break.
 *
 * <p>WHY RANDOM_PORT AND NOT THE DEFAULT MOCK SLICE — do not "simplify" this
 * back to MockMvc. Under {@code @SpringBootTest} with the default MOCK web
 * environment, Actuator's health endpoint registers but the Prometheus scrape
 * endpoint does not: it is contributed by
 * PrometheusMetricsExportAutoConfiguration, which is conditional on a real
 * servlet environment. The result is a test suite where {@code /healthz} passes
 * and {@code /metrics} returns 404 — the endpoint the ServiceScrapes read is
 * then effectively untested, and a genuine break would only surface as
 * Prometheus silently scraping a 404 in the cluster. Booting a real container
 * tests exactly what runs in the pod.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "APP_VERSION=git-testtag1234",
            "RELEASE_MODE=canary",
            "POD_NAME=app-abc123"
        })
class AppControllerTest {

    @Autowired private TestRestTemplate rest;

    @Test
    @DisplayName("GET / serves the landing page, and the injected version is visible on it")
    void landingPageShowsTheLiveVersion() {
        ResponseEntity<String> res = rest.getForEntity("/", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Proves the @GetMapping("/") wins over Actuator's root base-path.
        assertThat(res.getBody())
                .contains("git-testtag1234")
                .contains("canary")
                .contains("app-abc123");
    }

    @Test
    @DisplayName("GET /api/info returns the three facts the chart injects")
    void infoReturnsBuildFacts() {
        ResponseEntity<String> res = rest.getForEntity("/api/info", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .contains("\"version\":\"git-testtag1234\"")
                .contains("\"mode\":\"canary\"")
                .contains("\"pod\":\"app-abc123\"");
    }

    @Test
    @DisplayName("GET /healthz is UP — this is the path the chart's probes use")
    void healthzIsTheProbeEndpoint() {
        ResponseEntity<String> res = rest.getForEntity("/healthz", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("GET /metrics serves Prometheus text — this is what the ServiceScrapes read")
    void metricsIsPrometheusFormat() {
        ResponseEntity<String> res = rest.getForEntity("/metrics", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Prometheus text exposition format, not JSON — the scrape config in
        // gitops/scrapes/app-scrapes.yaml parses this directly.
        assertThat(res.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    @DisplayName("The readiness probe group resolves — a future chart split depends on it")
    void readinessGroupIsAvailable() {
        ResponseEntity<String> res = rest.getForEntity("/healthz/readiness", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }
}
