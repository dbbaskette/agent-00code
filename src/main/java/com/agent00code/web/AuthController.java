package com.agent00code.web;

import com.agent00code.config.AgentConfig;
import com.agent00code.config.McpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles OAuth authorization flows for MCP servers.
 * <p>
 * In the Spring Boot port, OAuth is primarily handled by Spring Security OAuth2 Client.
 * These endpoints provide the same user-facing URLs that the existing chat UI expects.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AgentConfig agentConfig;

    public AuthController(AgentConfig agentConfig) {
        this.agentConfig = agentConfig;
    }

    @GetMapping("/login/{serverName}")
    public ResponseEntity<String> login(@PathVariable String serverName) {
        McpServerConfig server = agentConfig.mcpServers().stream()
                .filter(s -> s.name().equals(serverName))
                .findFirst()
                .orElse(null);

        if (server == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body("Unknown server: " + serverName);
        }

        if (!"oauth".equals(server.auth())) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body("Server '" + serverName + "' does not use OAuth");
        }

        // Redirect to Spring Security's OAuth2 authorization endpoint
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "/oauth2/authorization/" + serverName)
                .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state) {

        if (error != null) {
            String desc = errorDescription != null ? errorDescription : error;
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(callbackPage(false,
                            "Authorization failed: " + desc));
        }

        if (code == null || state == null) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(callbackPage(false,
                            "Missing code or state parameter."));
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(callbackPage(true,
                        "Authorization complete. Tools will be refreshed automatically."));
    }

    private String callbackPage(boolean success, String message) {
        String icon = success ? "\u2713" : "\u2717";
        String colour = success ? "#22c55e" : "#ef4444";
        String title = success ? "Authorization Complete" : "Authorization Failed";

        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>%s</title>
                  <style>
                    body {font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;
                           min-height:100vh;margin:0;background:#0f172a;color:#e2e8f0}
                    .card {background:#1e293b;border-radius:12px;padding:2.5rem 3rem;text-align:center;
                            max-width:480px;box-shadow:0 4px 24px rgba(0,0,0,.4)}
                    .icon {font-size:3rem;color:%s}
                    h1 {margin:.5rem 0 1rem;font-size:1.4rem}
                    p {color:#94a3b8;line-height:1.6}
                    a {display:inline-block;margin-top:1.5rem;padding:.6rem 1.4rem;background:#6366f1;
                        color:#fff;border-radius:8px;text-decoration:none;font-weight:600}
                    a:hover {background:#4f46e5}
                  </style>
                </head>
                <body>
                  <div class="card">
                    <div class="icon">%s</div>
                    <h1>%s</h1>
                    <p>%s</p>
                    <a href="/">Back to chat</a>
                  </div>
                </body>
                </html>
                """.formatted(title, colour, icon, title, message);
    }
}
