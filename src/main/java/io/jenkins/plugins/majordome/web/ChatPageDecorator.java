package io.jenkins.plugins.majordome.web;

import hudson.Extension;
import hudson.model.PageDecorator;
import io.jenkins.plugins.majordome.MajorDomeConfiguration;
import io.jenkins.plugins.majordome.MajorDomePermissions;
import jenkins.model.Jenkins;

/** Adds the floating chat button to every Jenkins page (see footer.jelly). */
@Extension
public class ChatPageDecorator extends PageDecorator {

    public boolean isVisible() {
        MajorDomeConfiguration config = MajorDomeConfiguration.get();
        return config.isReady()
                && config.isShowFloatingWidget()
                && Jenkins.get().hasPermission(MajorDomePermissions.USE);
    }
}
