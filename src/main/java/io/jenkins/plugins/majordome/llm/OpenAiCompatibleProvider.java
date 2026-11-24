package io.jenkins.plugins.majordome.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.majordome.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Any server exposing the OpenAI Chat Completions API with function calling: OpenAI itself, or
 * self-hosted models through Ollama, vLLM, llama.cpp server, LM Studio, LocalAI, or an AI gateway.
 */
public class OpenAiCompatibleProvider extends LlmProvider {

    public static final String DEFAULT_BASE_URL = "http://localhost:11434/v1";

    private String baseUrl = DEFAULT_BASE_URL;
    private String model;
    private String credentialsId;
    private int maxTokens = 8192;
    private int timeoutSeconds = 300;

    @DataBoundConstructor
    public OpenAiCompatibleProvider(String baseUrl, String model) {
        this.baseUrl = Objects.requireNonNullElse(Util.fixEmptyAndTrim(baseUrl), DEFAULT_BASE_URL);
        this.model = Util.fixEmptyAndTrim(model);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    /** Optional: local servers such as Ollama usually need no key. */
    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    @DataBoundSetter
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens > 0 ? maxTokens : 8192;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    @DataBoundSetter
    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 300;
    }

    @Override
    public String fingerprint() {
        return "openai-compatible:" + baseUrl + ":" + model;
    }

    @Override
    public LlmResponse chat(LlmRequest request) throws LlmException, InterruptedException {
        if (model == null) {
            throw new LlmException("No model configured for the OpenAI-compatible provider");
        }
        ObjectNode body = Json.object();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.set("messages", toMessages(request));
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", spec.inputSchema());
            }
        }

        HttpRequest.Builder http = HttpRequest.newBuilder(URI.create(stripSlash(baseUrl) + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        String apiKey = secretFor(credentialsId);
        if (apiKey != null) {
            http.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            HttpClient client = ProxyConfiguration.newHttpClientBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
            response = client.send(http.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException("Cannot reach " + baseUrl + ": " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new LlmException("Model server returned HTTP " + response.statusCode() + ": "
                    + io.jenkins.plugins.majordome.util.Text.truncate(response.body(), 500));
        }
        try {
            return fromResponse(Json.parse(response.body()));
        } catch (IOException e) {
            throw new LlmException("Malformed response from model server", e);
        }
    }

    private static ArrayNode toMessages(LlmRequest request) {
        ArrayNode out = Json.MAPPER.createArrayNode();
        out.addObject().put("role", "system").put("content", request.system());
        for (ChatMessage m : request.messages()) {
            if (m.getRole() == ChatMessage.Role.ASSISTANT) {
                ObjectNode msg = out.addObject().put("role", "assistant");
                msg.put("content", m.getText());
                if (!m.getToolCalls().isEmpty()) {
                    ArrayNode calls = msg.putArray("tool_calls");
                    for (ToolCall call : m.getToolCalls()) {
                        ObjectNode c = calls.addObject().put("id", call.id()).put("type", "function");
                        c.putObject("function").put("name", call.name()).put("arguments", Json.write(call.input()));
                    }
                }
                continue;
            }
            for (ToolResult r : m.getToolResults()) {
                String content = r.error() ? "ERROR: " + r.content() : r.content();
                out.addObject().put("role", "tool").put("tool_call_id", r.toolCallId()).put("content", content);
            }
            if (m.getText() != null) {
                out.addObject().put("role", "user").put("content", m.getText());
            }
        }
        return out;
    }

    private static LlmResponse fromResponse(JsonNode json) throws LlmException {
        JsonNode choice = json.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (message.isMissingNode()) {
            throw new LlmException("Model server response has no choices");
        }
        List<ToolCall> calls = new ArrayList<>();
        int index = 0;
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode fn = call.path("function");
            String id = call.path("id").asText("call_" + index);
            index++;
            JsonNode input;
            try {
                String args = fn.path("arguments").asText("{}");
                input = Json.parse(args.isBlank() ? "{}" : args);
            } catch (IOException e) {
                // Small local models sometimes emit invalid JSON; the tool will reject it and the model can retry.
                input = Json.object().put("_invalid_arguments", fn.path("arguments").asText());
            }
            calls.add(new ToolCall(id, fn.path("name").asText(), input));
        }
        String text = message.path("content").isTextual() ? message.path("content").asText() : null;
        String finish = choice.path("finish_reason").asText("");
        LlmResponse.StopReason stop = switch (finish) {
            case "tool_calls", "function_call" -> LlmResponse.StopReason.TOOL_USE;
            case "stop" -> calls.isEmpty() ? LlmResponse.StopReason.END_TURN : LlmResponse.StopReason.TOOL_USE;
            case "length" -> LlmResponse.StopReason.MAX_TOKENS;
            case "content_filter" -> LlmResponse.StopReason.REFUSAL;
            default -> calls.isEmpty() ? LlmResponse.StopReason.OTHER : LlmResponse.StopReason.TOOL_USE;
        };
        JsonNode usage = json.path("usage");
        return new LlmResponse(
                ChatMessage.assistant(text, calls, null),
                stop,
                usage.path("prompt_tokens").asLong(0),
                usage.path("completion_tokens").asLong(0));
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Extension
    @Symbol("openaiCompatible")
    public static class DescriptorImpl extends LlmProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "OpenAI-compatible (OpenAI, Ollama, vLLM, LM Studio…)";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            return credentialItems(credentialsId);
        }

        @POST
        public FormValidation doCheckBaseUrl(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            try {
                URI uri = URI.create(value);
                if (!"https".equals(uri.getScheme()) && !isLocal(uri.getHost())) {
                    return FormValidation.warning("Plain HTTP to a remote host sends prompts, logs and the API key unencrypted");
                }
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Not a valid URL");
            }
        }

        private static boolean isLocal(String host) {
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
        }
    }
}
