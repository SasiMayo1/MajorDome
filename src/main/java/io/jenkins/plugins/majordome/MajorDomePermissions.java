package io.jenkins.plugins.majordome;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import jenkins.model.Jenkins;

/**
 * Permissions owned by MajorDome.
 *
 * <p>{@link #USE} only lets a user talk to the assistant. What the assistant can then read or do
 * is decided by the user's ordinary Jenkins permissions (Item/Read, Item/Build, Overall/Administer…).
 */
public final class MajorDomePermissions {

    public static final PermissionGroup GROUP =
            new PermissionGroup(MajorDomePermissions.class, Messages._MajorDome_PermissionGroup());

    /** Implied by Overall/Administer: admins decide who else may use the assistant. */
    public static final Permission USE = new Permission(
            GROUP,
            "Use",
            Messages._MajorDome_UsePermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);

    private MajorDomePermissions() {}

    /** Forces class loading so the permissions are registered before authorization strategies load. */
    @Initializer(before = InitMilestone.SYSTEM_CONFIG_LOADED)
    public static void register() {
        USE.getId();
    }
}
