package io.jenkins.plugins.majordome.agent;

import hudson.Extension;
import hudson.ExtensionList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversations by user id, in memory. Persistence to {@code $JENKINS_HOME} is planned (roadmap,
 * phase 1); provider-native message copies would then need their own serialization.
 */
@Extension
public class ConversationStore {

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public static ConversationStore get() {
        return ExtensionList.lookupSingleton(ConversationStore.class);
    }

    public Conversation forUser(String userId) {
        return conversations.computeIfAbsent(userId, Conversation::new);
    }

    public void reset(String userId) {
        Conversation conversation = conversations.get(userId);
        if (conversation != null) {
            synchronized (conversation) {
                conversation.clear();
            }
        }
    }
}
