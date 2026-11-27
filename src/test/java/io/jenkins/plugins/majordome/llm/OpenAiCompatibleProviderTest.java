package io.jenkins.plugins.majordome.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.jenkins.plugins.majordome.util.Json;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The OpenAI-compatible provider against a local fake server, in streaming and non-streaming mode. */
@WithJenkins
class OpenAiCompatibleProviderTest {

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String contentType = "text/event-stream";
    private volatile String responseBody = "";

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private OpenAiCompatibleProvider provider() {
        return new OpenAiCompatibleProvider("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model");
    }

    private static LlmRequest request() {
        ToolSpec getJob = new ToolSpec("get_job", "Get a job", Json.object().put("type", "object"));
        return new LlmRequest("system prompt", List.of(ChatMessage.user("how is app?")), List.of(getJob));
    }

    private static String sse(String... chunks) {
        StringBuilder sb = new StringBuilder();
        for (String c : chunks) {
            sb.append("data: ").append(c).append("\n\n");
        }
        return sb.append("data: [DONE]\n\n").toString();
    }

    @Test
    void streamsTextAndReassemblesSplitToolCalls() throws Exception {
        // OpenAI style: the tool call arrives in pieces keyed by index; usage comes in a final chunk.
        responseBody = sse(
                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Let me \"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"check.\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_job\",\"arguments\":\"\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"job\\\":\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"app\\\"}\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":321,\"completion_tokens\":12}}");
        List<String> deltas = new ArrayList<>();

        LlmResponse response = provider().chat(request(), deltas::add);

        assertEquals(List.of("Let me ", "check."), deltas);
        assertEquals("Let me check.", response.message().getText());
        assertEquals(1, response.message().getToolCalls().size());
        ToolCall call = response.message().getToolCalls().get(0);
        assertEquals("call_a", call.id());
        assertEquals("get_job", call.name());
        assertEquals("app", call.input().path("job").asText());
        assertEquals(LlmResponse.StopReason.TOOL_USE, response.stopReason());
        assertEquals(321, response.inputTokens());
        assertEquals(12, response.outputTokens());

        JsonNode sent = requests.get(0);
        assertTrue(sent.path("stream").asBoolean());
        assertTrue(sent.path("stream_options").path("include_usage").asBoolean());
        assertEquals("get_job", sent.path("tools").path(0).path("function").path("name").asText());
    }

    @Test
    void acceptsWholeToolCallsInOneChunk() throws Exception {
        // Ollama style: no index, the whole call in one chunk, finish_reason "stop".
        responseBody = sse(
                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
                        + "{\"id\":\"call_x\",\"function\":{\"name\":\"get_job\",\"arguments\":\"{\\\"job\\\":\\\"app\\\"}\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}");
        List<String> deltas = new ArrayList<>();

        LlmResponse response = provider().chat(request(), deltas::add);

        assertTrue(deltas.isEmpty(), "empty content pieces are not reported");
        assertNull(response.message().getText());
        assertEquals("app", response.message().getToolCalls().get(0).input().path("job").asText());
        assertEquals(LlmResponse.StopReason.TOOL_USE, response.stopReason());
    }

    @Test
    void invalidArgumentsAreKeptForTheToolToReject() throws Exception {
        responseBody = sse("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c\","
                + "\"function\":{\"name\":\"get_job\",\"arguments\":\"{job: app\"}}]},\"finish_reason\":\"tool_calls\"}]}");

        LlmResponse response = provider().chat(request(), d -> {});

        assertEquals("{job: app", response.message().getToolCalls().get(0).input().path("_invalid_arguments").asText());
    }

    @Test
    void streamingCanBeTurnedOff() throws Exception {
        contentType = "application/json";
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello.\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";
        OpenAiCompatibleProvider provider = provider();
        provider.setStream(false);
        List<String> deltas = new ArrayList<>();

        LlmResponse response = provider.chat(request(), deltas::add);

        assertTrue(deltas.isEmpty(), "nothing streamed: the caller shows the whole text");
        assertEquals("Hello.", response.message().getText());
        assertEquals(LlmResponse.StopReason.END_TURN, response.stopReason());
        assertFalse(requests.get(0).has("stream"));
    }

    @Test
    void httpErrorWhileStreamingIsReported() {
        status = 500;
        contentType = "application/json";
        responseBody = "{\"error\":\"model not loaded\"}";

        LlmException e = assertThrows(LlmException.class, () -> provider().chat(request(), d -> {}));

        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
        assertTrue(e.getMessage().contains("model not loaded"), e.getMessage());
    }

    @Test
    void errorChunkIsReported() {
        responseBody = sse("{\"error\":{\"message\":\"out of memory\"}}");

        LlmException e = assertThrows(LlmException.class, () -> provider().chat(request(), d -> {}));

        assertTrue(e.getMessage().contains("out of memory"), e.getMessage());
    }
}
