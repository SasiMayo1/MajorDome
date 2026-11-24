package io.jenkins.plugins.majordome.agent;

import io.jenkins.plugins.majordome.actions.PendingAction;
import java.util.List;

/** Outcome of one user message: the reply, the proposals it produced, and token usage. */
public record TurnResult(
        String reply, List<PendingAction> proposals, List<String> toolsUsed, long inputTokens, long outputTokens) {

    public TurnResult {
        proposals = List.copyOf(proposals);
        toolsUsed = List.copyOf(toolsUsed);
    }
}
