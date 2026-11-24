package io.jenkins.plugins.majordome.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import hudson.model.Item;
import hudson.util.Secret;
import io.jenkins.plugins.majordome.MajorDomeConfiguration;
import io.jenkins.plugins.majordome.MajorDomePermissions;
import io.jenkins.plugins.majordome.util.Json;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Runs the real {@link AnthropicProvider} (official SDK, inside the Jenkins classloader) against a local
 * server that speaks the Messages API, so the wire format is checked without an API key.
 */
@WithJenkins
class AnthropicProviderTest {

    private static final String API_KEY = "sk-ant-test-0000000000000000";

    private record Recorded(Headers headers, JsonNode body) {}

    private record Canned(int status, String json) {}

    private JenkinsRule j;
    private HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Deque<Canned> responses = new ArrayDeque<>();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, MajorDomePermissions.USE).everywhere().to("dev"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // Copy: the exchange's header map is not guaranteed to outlive the exchange.
            Headers headers = new Headers();
            exchange.getRequestHeaders().forEach(headers::put);
            requests.add(new Recorded(headers, Json.parse(body)));
            Canned next;
            synchronized (responses) {
                next = responses.isEmpty() ? new Canned(500, "{}") : responses.poll();
            }
            byte[] out = next.json().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(next.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();

        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "anthropic-test", "test key", Secret.fromString(API_KEY)));
        SystemCredentialsProvider.getInstance().save();

        AnthropicProvider provider = new AnthropicProvider("anthropic-test");
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        MajorDomeConfiguration.get().setProvider(provider);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(int status, String json) {
        synchronized (responses) {
            responses.add(new Canned(status, json));
        }
    }

    private static String message(String id, String stopReason, String content) {
        return "{\"id\":\"" + id + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                + "\"content\":" + content + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":30}}";
    }

    private Page chat(String text) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("dev").withThrowExceptionOnFailingStatusCode(false);
        JsonNode issuer = Json.parse(wc.goTo("crumbIssuer/api/json", "application/json")
                .getWebResponse().getContentAsString());
        WebRequest req = new WebRequest(new URL(j.getURL(), "majordome/chat"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setAdditionalHeader(issuer.path("crumbRequestField").asText(), issuer.path("crumb").asText());
        req.setRequestBody(Json.object().put("message", text).toString());
        return wc.getPage(req);
    }

    @Test
    void toolLoopOverTheWire() throws Exception {
        j.createFreeStyleProject("demo");
        respond(200, message("msg_1", "tool_use",
                "[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_job\",\"input\":{\"job\":\"demo\"}}]"));
        respond(200, message("msg_2", "end_turn",
                "[{\"type\":\"text\",\"text\":\"The job demo has never been built.\"}]"));

        Page page = chat("Tell me about the job demo");

        assertEquals(200, page.getWebResponse().getStatusCode(), page.getWebResponse().getContentAsString());
        JsonNode reply = Json.parse(page.getWebResponse().getContentAsString());
        assertEquals("The job demo has never been built.", reply.path("reply").asText());
        assertEquals(2, requests.size());

        // First request: auth header, beta header, cached system prompt, effort, tool schema.
        Recorded first = requests.get(0);
        // The SDK picks the auth header (x-api-key, or Authorization: Bearer in 3.x); either carries the key.
        String auth = first.headers().getFirst("Authorization");
        assertTrue(API_KEY.equals(first.headers().getFirst("x-api-key")) || ("Bearer " + API_KEY).equals(auth),
                "API key sent in an auth header");
        assertTrue(first.headers().containsKey("Anthropic-version"));
        assertTrue(first.headers().getFirst("anthropic-beta").contains(AnthropicProvider.FALLBACK_BETA));
        JsonNode body = first.body();
        assertEquals("claude-opus-5-5", body.path("model").asText());
        assertEquals("ephemeral", body.path("system").path(0).path("cache_control").path("type").asText());
        assertEquals("medium", body.path("output_config").path("effort").asText());
        assertEquals("default", body.path("fallbacks").asText());
        JsonNode getJob = null;
        for (JsonNode tool : body.path("tools")) {
            if ("get_job".equals(tool.path("name").asText())) {
                getJob = tool;
            }
        }
        assertTrue(getJob != null, "get_job offered to the model");
        assertEquals("job", getJob.path("input_schema").path("required").path(0).asText());
        assertFalse(getJob.path("input_schema").path("additionalProperties").asBoolean(true));
        assertFalse(body.toString().contains(API_KEY), "API key only in the header");

        // Second request: the assistant tool_use is echoed back, followed by the tool_result.
        JsonNode messages = requests.get(1).body().path("messages");
        assertEquals(3, messages.size());
        assertEquals("assistant", messages.path(1).path("role").asText());
        assertEquals("toolu_1", messages.path(1).path("content").path(0).path("id").asText());
        JsonNode result = messages.path(2).path("content").path(0);
        assertEquals("tool_result", result.path("type").asText());
        assertEquals("toolu_1", result.path("tool_use_id").asText());
        assertTrue(result.path("content").toString().contains("demo"));
    }

    @Test
    void writeToolIsOnlyProposed() throws Exception {
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, Item.BUILD, MajorDomePermissions.USE).everywhere().to("dev"));
        var job = j.createFreeStyleProject("demo");
        respond(200, message("msg_1", "tool_use",
                "[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"trigger_build\",\"input\":{\"job\":\"demo\"}}]"));
        respond(200, message("msg_2", "end_turn",
                "[{\"type\":\"text\",\"text\":\"I proposed a build; please confirm.\"}]"));

        Page page = chat("Build demo");

        assertEquals(200, page.getWebResponse().getStatusCode(), page.getWebResponse().getContentAsString());
        JsonNode reply = Json.parse(page.getWebResponse().getContentAsString());
        assertEquals(1, reply.path("proposals").size());
        assertEquals("trigger_build", reply.path("proposals").path(0).path("tool").asText());
        j.waitUntilNoActivity();
        assertEquals(0, job.getBuilds().size(), "nothing runs before the user confirms");
    }

    @Test
    void authenticationErrorIsReported() throws Exception {
        respond(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");

        Page page = chat("hi");

        assertEquals(502, page.getWebResponse().getStatusCode());
        String body = page.getWebResponse().getContentAsString();
        assertTrue(body.contains("401"), body);
        assertFalse(body.contains(API_KEY), "the key never appears in the error");
    }
}
