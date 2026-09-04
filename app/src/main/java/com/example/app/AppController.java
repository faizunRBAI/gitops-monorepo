package com.example.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public HTTP surface.
 *
 * <p>Deliberately identical to the contract the previous Node service exposed,
 * so chart/app, gitops/scrapes and the pipeline's verify stage did not have to
 * change when the runtime did:
 *
 * <ul>
 *   <li>{@code GET /} — branded landing page showing the live version</li>
 *   <li>{@code GET /api/info} — the same three facts as JSON</li>
 *   <li>{@code GET /healthz} — served by Actuator (see application.properties)</li>
 *   <li>{@code GET /metrics} — served by Actuator + Micrometer</li>
 * </ul>
 */
@RestController
public class AppController {

    private final BuildInfo buildInfo;

    public AppController(BuildInfo buildInfo) {
        this.buildInfo = buildInfo;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String landing() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>gitops-monorepo</title>
                <style>
                  :root { color-scheme: dark; }
                  body { margin:0; min-height:100vh; display:grid; place-items:center;
                         font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                         background:#0d1117; color:#e6edf3; }
                  .card { max-width:44rem; padding:2.5rem; border:1px solid #30363d;
                          border-radius:12px; background:#161b22; }
                  h1 { margin:0 0 .25rem; font-size:1.5rem; }
                  p.sub { margin:0 0 1.75rem; color:#8b949e; }
                  dl { display:grid; grid-template-columns:auto 1fr; gap:.6rem 1.25rem; margin:0 0 1.75rem; }
                  dt { color:#8b949e; }
                  dd { margin:0; }
                  .tag { display:inline-block; padding:.15rem .55rem; border-radius:999px;
                         background:#1f6feb22; border:1px solid #1f6feb; color:#79c0ff; }
                  ul { margin:0; padding-left:1.1rem; color:#8b949e; line-height:1.8; }
                  a { color:#79c0ff; }
                </style>
                </head>
                <body>
                  <main class="card">
                    <h1>gitops-monorepo</h1>
                    <p class="sub">One repository: application, chart, cluster config, pipeline.</p>
                    <dl>
                      <dt>runtime</dt><dd>Spring Boot 3 &middot; Java 21</dd>
                      <dt>version</dt><dd><span class="tag">%s</span></dd>
                      <dt>release mode</dt><dd>%s</dd>
                      <dt>pod</dt><dd>%s</dd>
                    </dl>
                    <ul>
                      <li>CI built and pushed this image, then patched the ArgoCD Application's
                          Helm parameter &mdash; it did not commit to Git.</li>
                      <li>ArgoCD reconciled the cluster to match. <code>selfHeal</code> is on.</li>
                      <li><a href="/healthz">/healthz</a> &middot; <a href="/metrics">/metrics</a>
                          &middot; <a href="/api/info">/api/info</a></li>
                    </ul>
                  </main>
                </body>
                </html>
                """
                .formatted(buildInfo.version(), buildInfo.releaseMode(), buildInfo.podName());
    }

    /**
     * A LinkedHashMap, not Map.of(): Map.of() has a randomized iteration order,
     * so the serialized key order would differ between JVM runs. The verify
     * stage greps this response for the deployed tag, and anyone diffing it
     * across deploys deserves a stable shape.
     */
    @GetMapping(value = "/api/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> info() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("version", buildInfo.version());
        body.put("mode", buildInfo.releaseMode());
        body.put("pod", buildInfo.podName());
        return body;
    }
}
