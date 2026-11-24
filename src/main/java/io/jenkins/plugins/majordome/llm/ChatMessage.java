package io.jenkins.plugins.majordome.llm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral conversation message.
 *
 * <ul>
 *   <li>USER messages carry text, tool results, or both (tool results first).
 *   <li>ASSISTANT messages carry text and/or tool calls, plus an optional provider-native copy of the
 *       message. Providers that must replay the exact message (e.g. Anthropic, whose thinking blocks
 *       must be passed back unchanged) use that copy instead of rebuilding it from the neutral fields.
 * </ul>
 */
public final class ChatMessage {

    public enum Role {
        USER,
        ASSISTANT
    }

    private final Role role;
    private String text;
    private final List<ToolCall> toolCalls;
    private final List<ToolResult> toolResults;
    private final transient Object nativeMessage;

    private ChatMessage(
            Role role, String text, List<ToolCall> toolCalls, List<ToolResult> toolResults, Object nativeMessage) {
        this.role = role;
        this.text = text;
        this.toolCalls = List.copyOf(toolCalls);
        this.toolResults = List.copyOf(toolResults);
        this.nativeMessage = nativeMessage;
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, List.of(), List.of(), null);
    }

    public static ChatMessage toolResults(List<ToolResult> results) {
        return new ChatMessage(Role.USER, null, List.of(), results, null);
    }

    public static ChatMessage assistant(
            @CheckForNull String text, List<ToolCall> toolCalls, @CheckForNull Object nativeMessage) {
        return new ChatMessage(Role.ASSISTANT, text, toolCalls, List.of(), nativeMessage);
    }

    public Role getRole() {
        return role;
    }

    @CheckForNull
    public String getText() {
        return text;
    }

    /**
     * Adds user text to a USER message that so far only holds tool results. Used when a turn ended
     * on tool results (iteration limit), so the next user text joins that message instead of
     * producing two consecutive user messages.
     */
    void appendUserText(String more) {
        if (role != Role.USER) {
            throw new IllegalStateException("Only user messages accept appended text");
        }
        this.text = text == null ? more : text + "\n\n" + more;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public List<ToolResult> getToolResults() {
        return toolResults;
    }

    @CheckForNull
    public Object getNativeMessage() {
        return nativeMessage;
    }

    public boolean isPlainUserText() {
        return role == Role.USER && toolResults.isEmpty() && text != null;
    }

    /** Appends user text to a history, merging into a trailing tool-results message if there is one. */
    public static void addUserText(List<ChatMessage> history, String text) {
        if (!history.isEmpty()) {
            ChatMessage last = history.get(history.size() - 1);
            if (last.role == Role.USER) {
                last.appendUserText(text);
                return;
            }
        }
        history.add(user(text));
    }

    /** Copy helper for callers that need a mutable list. */
    public static List<ChatMessage> mutableCopy(List<ChatMessage> messages) {
        return new ArrayList<>(messages);
    }
}
