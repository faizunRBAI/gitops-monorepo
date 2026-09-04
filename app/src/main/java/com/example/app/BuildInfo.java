package com.example.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The three facts the chart injects into every pod.
 *
 * <p>APP_VERSION is the image tag that CI patched onto the ArgoCD Application's
 * Helm parameters, so a page served with a given version is direct evidence of
 * which deploy is live. RELEASE_MODE tells you whether a canary, blue/green or
 * plain Deployment produced this pod, and POD_NAME distinguishes canary pods
 * from stable ones during a rollout.
 *
 * <p>Defaults match local development, where none of these are set.
 */
@Component
public class BuildInfo {

    private final String version;
    private final String releaseMode;
    private final String podName;

    public BuildInfo(
            @Value("${APP_VERSION:dev}") String version,
            @Value("${RELEASE_MODE:plain}") String releaseMode,
            @Value("${POD_NAME:local}") String podName) {
        this.version = version;
        this.releaseMode = releaseMode;
        this.podName = podName;
    }

    public String version() {
        return version;
    }

    public String releaseMode() {
        return releaseMode;
    }

    public String podName() {
        return podName;
    }
}
