package io.jenkins.plugins.majordome.llm;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.DescriptorExtensionList;
import hudson.ExtensionPoint;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * A model backend. Implementations translate the provider-neutral {@link LlmRequest} into their own
 * API and back. Other plugins can contribute providers by extending this class.
 */
public abstract class LlmProvider implements Describable<LlmProvider>, ExtensionPoint {

    /**
     * Sends one request and returns the assistant message. Called on the HTTP request thread of the
     * user who is chatting, so implementations must apply timeouts.
     */
    public abstract LlmResponse chat(LlmRequest request) throws LlmException, InterruptedException;

    /**
     * Identifies the backend and model. When it changes, conversations started with another backend
     * are reset, because provider-native message copies cannot be replayed elsewhere.
     */
    public abstract String fingerprint();

    @Override
    public LlmProviderDescriptor getDescriptor() {
        return (LlmProviderDescriptor) Jenkins.get().getDescriptorOrDie(getClass());
    }

    public static DescriptorExtensionList<LlmProvider, LlmProviderDescriptor> all() {
        return Jenkins.get().getDescriptorList(LlmProvider.class);
    }

    /** Resolves a Secret text credential stored at the Jenkins root. */
    @CheckForNull
    protected static String secretFor(@CheckForNull String credentialsId) {
        if (Util.fixEmptyAndTrim(credentialsId) == null) {
            return null;
        }
        StringCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
        return credentials == null ? null : credentials.getSecret().getPlainText();
    }

    public abstract static class LlmProviderDescriptor extends Descriptor<LlmProvider> {

        /** Lists Secret text credentials; only administrators may enumerate them. */
        protected static ListBoxModel credentialItems(@CheckForNull String currentValue) {
            StandardListBoxModel model = new StandardListBoxModel();
            String current = Util.fixNull(currentValue);
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return model.includeCurrentValue(current);
            }
            return model.includeEmptyValue()
                    .includeAs(ACL.SYSTEM2, Jenkins.get(), StringCredentials.class)
                    .includeCurrentValue(current);
        }
    }
}
