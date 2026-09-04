package com.example.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * These tests pin the HTTP contract that the Helm chart, the ServiceScrapes and
 * the pipeline's verify stage all depend on. If any of them fails, something
 * downstream in the platform is about to break.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "APP_VERSION=git-testtag1234",
            "RELEASE_MODE=canary",
            "POD_NAME=app-abc123"
        })
class AppControllerTest {

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    private MockMvc mvc() {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mockMvc;
    }

    @Test
    @DisplayName("GET / serves the landing page, and the injected version is visible on it")
    void landingPageShowsTheLiveVersion() throws Exception {
        mvc().perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("git-testtag1234")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("canary")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app-abc123")));
    }

    @Test
    @DisplayName("GET /api/info returns the three facts the chart injects")
    void infoReturnsBuildFacts() throws Exception {
        mvc().perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("git-testtag1234"))
                .andExpect(jsonPath("$.mode").value("canary"))
                .andExpect(jsonPath("$.pod").value("app-abc123"));
    }

    @Test
    @DisplayName("GET /healthz is UP — this is the path the chart's probes use")
    void healthzIsTheProbeEndpoint() throws Exception {
        mvc().perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /metrics serves Prometheus text — this is what the ServiceScrapes read")
    void metricsIsPrometheusFormat() throws Exception {
        mvc().perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
    }
}
