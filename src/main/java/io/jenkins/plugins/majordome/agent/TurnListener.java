package io.jenkins.plugins.majordome.agent;

import io.jenkins.plugins.majordome.actions.PendingAction;

/**
 * Progress of one chat turn, for streaming it to the browser. Called on the thread running the turn,
 * while the conversation is locked: implementations must be quick and must not call back into the
 * conversation. The final {@link TurnResult} stays the authoritative outcome.
 */
public interface TurnListener {

    TurnListener NONE = new TurnListener() {};

    /** A piece of the assistant's reply, in order. Pieces of consecutive model calls are separated by a blank line. */
    default void onText(String delta) {}

    /** A tool is about to run (or, for a write tool, to be turned into a proposal). */
    default void onToolStarted(String toolName) {}

    /** A proposal was created and is waiting for the user. */
    default void onProposal(PendingAction proposal) {}
}
