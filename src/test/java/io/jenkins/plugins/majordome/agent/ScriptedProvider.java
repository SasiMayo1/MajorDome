package io.jenkins.plugins.majordome.agent;

import io.jenkins.plugins.majordome.llm.ChatMessage;
import io.jenkins.plugins.majordome.llm.LlmProvider;
import io.jenkins.plugins.majordome.llm.LlmRequest;
import io.jenkins.plugins.majordome.llm.LlmResponse;
import io.jenkins.plugins.majordome.llm.ToolCall;
import io.jenkins.plugins.majordome.llm.ToolResult;
import io.jenkins.plugins.majordome.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A fake model that replays scripted responses and records every request it receives. */
public class ScriptedProvider extends LlmProvider {

    private final transient Deque<LlmResponse> script = new ArrayDeque<>();
    private final transient List<LlmRequest> requests = new ArrayList<>();
    private transient int ids;

    public ScriptedProvider toolCall(String name, String argsJson) {
        try {
            ToolCall call = new ToolCall("call_" + (++ids), name, Json.parse(argsJson));
            script.add(new LlmResponse(
                    ChatMessage.assistant(null, List.of(call), null), LlmResponse.StopReason.TOOL_USE, 10, 5));
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ScriptedProvider text(String text) {
        script.add(new LlmResponse(ChatMessage.assistant(text, List.of(), null), LlmResponse.StopReason.END_TURN, 10, 5));
        return this;
    }

    @Override
    public synchronized LlmResponse chat(LlmRequest request) {
        requests.add(request);
        if (script.isEmpty()) {
            throw new AssertionError("Unexpected extra model call");
        }
        return script.removeFirst();
    }

    @Override
    public String fingerprint() {
        return "scripted";
    }

    public List<LlmRequest> requests() {
        return requests;
    }

    /** Tool results the model received in the most recent request. */
    public List<ToolResult> lastToolResults() {
        List<ChatMessage> messages = requests.get(requests.size() - 1).messages();
        return messages.get(messages.size() - 1).getToolResults();
    }

    /** Names of the tools offered in the first request. */
    public List<String> offeredTools() {
        return requests.get(0).tools().stream().map(t -> t.name()).toList();
    }
}
