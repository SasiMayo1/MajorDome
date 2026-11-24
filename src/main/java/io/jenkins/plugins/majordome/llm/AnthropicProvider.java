package io.jenkins.plugins.majordome.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.majordome.util.Json;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** Claude through the official Anthropic Java SDK. */
public class AnthropicProvider extends LlmProvider {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private String credentialsId;
    private String model = DEFAULT_MODEL;
    private String baseUrl;
    private int maxTokens = 16000;
    private String effort = "medium";
    private boolean serverFallbacks = true;

    private transient volatile AnthropicClient client;
    private transient volatile String clientKey;
    /** Static: XStream does not run initializers of transient fields when loading the configuration. */
    private static final Object CLIENT_LOCK = new Object();

    @DataBoundConstructor
    public AnthropicProvider(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getModel() {
        return model;
    }

    @DataBoundSetter
    public void setModel(String model) {
        this.model = Objects.requireNonNullElse(Util.fixEmptyAndTrim(model), DEFAULT_MODEL);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /** Optional: an AI gateway in front of the Anthropic API. */
    @DataBoundSetter
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = Util.fixEmptyAndTrim(baseUrl);
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    @DataBoundSetter
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens > 0 ? maxTokens : 16000;
    }

    public String getEffort() {
        return effort;
    }

    @DataBoundSetter
    public void setEffort(String effort) {
        this.effort = Objects.requireNonNullElse(Util.fixEmptyAndTrim(effort), "medium");
    }

    public boolean isServerFallbacks() {
        return serverFallbacks;
    }

    /** On a refusal, lets the API retry the request on a fallback model it picks. */
    @DataBoundSetter
    public void setServerFallbacks(boolean serverFallbacks) {
        this.serverFallbacks = serverFallbacks;
    }

    @Override
    public String fingerprint() {
        return "anthropic:" + model;
    }

    @Override
    public LlmResponse chat(LlmRequest request) throws LlmException {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens((long) maxTokens)
                // The system prompt is identical for every request: cache it.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.system())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(effort))
                        .build())
                .messages(toParams(request.messages()));
        for (ToolSpec spec : request.tools()) {
            params.addTool(toTool(spec));
        }
        if (serverFallbacks) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        Message response;
        try {
            response = client().messages().create(params.build());
        } catch (AnthropicServiceException e) {
            throw new LlmException("Anthropic API error (HTTP " + e.statusCode() + "): " + e.getMessage(), e);
        } catch (AnthropicException e) {
            throw new LlmException("Cannot reach the Anthropic API: " + e.getMessage(), e);
        }
        return fromResponse(response);
    }

    private static List<MessageParam> toParams(List<ChatMessage> messages) throws LlmException {
        List<MessageParam> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            if (m.getRole() == ChatMessage.Role.ASSISTANT) {
                if (!(m.getNativeMessage() instanceof MessageParam nativeParam)) {
                    // Can happen only if a conversation outlived a provider switch; the caller resets it.
                    throw new LlmException("Conversation contains a message from another provider");
                }
                out.add(nativeParam);
                continue;
            }
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (ToolResult r : m.getToolResults()) {
                blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(r.toolCallId())
                        .content(r.content())
                        .isError(r.error())
                        .build()));
            }
            String text = m.getText();
            if (text != null) {
                blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()));
            }
            out.add(MessageParam.ofUserOfContentBlockParams(blocks));
        }
        return out;
    }

    private static Tool toTool(ToolSpec spec) {
        ObjectNode schema = spec.inputSchema();
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        JsonNode properties = schema.path("properties");
        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            props.putAdditionalProperty(e.getKey(), JsonValue.from(Json.MAPPER.convertValue(e.getValue(), Object.class)));
        }
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private static LlmResponse fromResponse(Message response) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(t.text());
            });
            block.toolUse().ifPresent(tu ->
                    calls.add(new ToolCall(tu.id(), tu.name(), Json.MAPPER.valueToTree(tu._input()))));
        }
        LlmResponse.StopReason stop = response.stopReason()
                .map(AnthropicProvider::mapStopReason)
                .orElse(LlmResponse.StopReason.OTHER);
        ChatMessage message = ChatMessage.assistant(
                text.length() == 0 ? null : text.toString(), calls, response.toParam());
        return new LlmResponse(
                message, stop, response.usage().inputTokens(), response.usage().outputTokens());
    }

    private static LlmResponse.StopReason mapStopReason(StopReason reason) {
        if (StopReason.TOOL_USE.equals(reason)) {
            return LlmResponse.StopReason.TOOL_USE;
        }
        if (StopReason.END_TURN.equals(reason)) {
            return LlmResponse.StopReason.END_TURN;
        }
        if (StopReason.MAX_TOKENS.equals(reason)) {
            return LlmResponse.StopReason.MAX_TOKENS;
        }
        if (StopReason.REFUSAL.equals(reason)) {
            return LlmResponse.StopReason.REFUSAL;
        }
        return LlmResponse.StopReason.OTHER;
    }

    private AnthropicClient client() throws LlmException {
        String apiKey = secretFor(credentialsId);
        if (apiKey == null) {
            throw new LlmException("No Anthropic API key configured (credential '" + credentialsId + "' not found)");
        }
        String url = baseUrl;
        // Rebuild the client when the key or endpoint changes (e.g. credential rotated).
        String key = Util.getDigestOf(apiKey + "|" + url);
        AnthropicClient current = client;
        if (current != null && key.equals(clientKey)) {
            return current;
        }
        synchronized (CLIENT_LOCK) {
            if (client != null && key.equals(clientKey)) {
                return client;
            }
            AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .timeout(Duration.ofMinutes(5));
            if (url != null) {
                builder.baseUrl(url);
            }
            ProxyConfiguration proxy = Jenkins.get().getProxy();
            if (proxy != null) {
                String host = URI.create(url != null ? url : "https://api.anthropic.com").getHost();
                builder.proxy(proxy.createProxy(host));
            }
            AnthropicClient old = client;
            client = builder.build();
            clientKey = key;
            if (old != null) {
                old.close();
            }
            return client;
        }
    }

    @Extension
    @Symbol("anthropic")
    public static class DescriptorImpl extends LlmProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Anthropic (Claude)";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            return credentialItems(credentialsId);
        }

        @POST
        public ListBoxModel doFillEffortItems() {
            ListBoxModel items = new ListBoxModel();
            for (String level : List.of("low", "medium", "high", "xhigh", "max")) {
                items.add(level);
            }
            return items;
        }

        @POST
        public FormValidation doCheckCredentialsId(@QueryParameter @CheckForNull String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            return Util.fixEmptyAndTrim(value) == null
                    ? FormValidation.error("Select a Secret text credential holding the Anthropic API key")
                    : FormValidation.ok();
        }
    }
}
