package com.example.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
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
 * back to MockMvc. The MOCK web environment does not start a real servlet
 * container, so a probe path that works here can still 404 in the pod. Booting
 * real Tomcat tests exactly what runs in production.
 *
 * <p>WHY THE DIAGNOSTIC TEST BELOW REFERENCES NO PROMETHEUS CLASS — Micrometer
 * 1.13 (managed by Boot 3.3) relocated PrometheusMeterRegistry from
 * io.micrometer.prometheus to io.micrometer.prometheusmetrics. Importing either
 * package to assert on the bean is a compile-time bet on a coordinate that
 * cannot be verified from the build sandbox, which has no JVM. The endpoint
 * registry is queried through Actuator's own stable API instead, so a wrong
 * guess is impossible.
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

    @Autowired private WebEndpointsSupplier webEndpoints;

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
    @DisplayName("The readiness probe group resolves — a future chart split depends on it")
    void readinessGroupIsAvailable() {
        ResponseEntity<String> res = rest.getForEntity("/healthz/readiness", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }

    /**
     * Runs the same question the Boot startup log answers with the unhelpful
     * line "Exposing 2 endpoints beneath base path ''" — but names the
     * endpoints, so a failure states WHICH one is missing instead of leaving a
     * bare 404 to be guessed at. Two CI attempts were already spent on that
     * ambiguity, and the build sandbox has no JVM to reproduce it locally.
     */
    @Test
    @DisplayName("The 'prometheus' endpoint id is registered and exposed over the web")
    void prometheusEndpointIsRegistered() {
        List<String> exposed =
                webEndpoints.getEndpoints().stream()
                        .map(ExposableWebEndpoint::getEndpointId)
                        .map(Object::toString)
                        .toList();

        assertThat(exposed)
                .as(
                        "Actuator exposed %s. 'prometheus' missing means the scrape endpoint bean"
                            + " was never created, so management.endpoints.web.path-mapping"
                            + ".prometheus=metrics has nothing to map and the ServiceScrapes in"
                            + " gitops/scrapes/app-scrapes.yaml would poll a 404 forever.",
                        exposed)
                .contains("prometheus");
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
}
