package io.jenkins.plugins.majordome;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import io.jenkins.plugins.majordome.llm.LlmProvider;
import jenkins.model.GlobalConfiguration;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;

/** Manage Jenkins → System → MajorDome. Also configurable with Configuration as Code under {@code majordome:}. */
@Extension
@Symbol("majordome")
public class MajorDomeConfiguration extends GlobalConfiguration {

    private boolean enabled = true;
    private LlmProvider provider;
    private int maxToolIterations = 10;
    private int proposalTtlMinutes = 10;
    private boolean allowBuildLogs = true;
    private boolean showFloatingWidget = true;

    public MajorDomeConfiguration() {
        load();
    }

    public static MajorDomeConfiguration get() {
        return ExtensionList.lookupSingleton(MajorDomeConfiguration.class);
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "MajorDome";
    }

    public boolean isEnabled() {
        return enabled;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    @CheckForNull
    public LlmProvider getProvider() {
        return provider;
    }

    @DataBoundSetter
    public void setProvider(LlmProvider provider) {
        this.provider = provider;
        save();
    }

    public int getMaxToolIterations() {
        return maxToolIterations;
    }

    /** Upper bound on model ↔ tool round trips per user message. */
    @DataBoundSetter
    public void setMaxToolIterations(int maxToolIterations) {
        this.maxToolIterations = Math.max(1, Math.min(50, maxToolIterations));
        save();
    }

    public int getProposalTtlMinutes() {
        return proposalTtlMinutes;
    }

    @DataBoundSetter
    public void setProposalTtlMinutes(int proposalTtlMinutes) {
        this.proposalTtlMinutes = Math.max(1, Math.min(120, proposalTtlMinutes));
        save();
    }

    public boolean isAllowBuildLogs() {
        return allowBuildLogs;
    }

    /** Whether console logs (redacted) may be sent to the model provider. */
    @DataBoundSetter
    public void setAllowBuildLogs(boolean allowBuildLogs) {
        this.allowBuildLogs = allowBuildLogs;
        save();
    }

    public boolean isShowFloatingWidget() {
        return showFloatingWidget;
    }

    @DataBoundSetter
    public void setShowFloatingWidget(boolean showFloatingWidget) {
        this.showFloatingWidget = showFloatingWidget;
        save();
    }

    public boolean isReady() {
        return enabled && provider != null;
    }
}
