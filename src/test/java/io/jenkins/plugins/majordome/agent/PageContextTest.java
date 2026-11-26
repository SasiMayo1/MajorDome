package io.jenkins.plugins.majordome.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.majordome.MajorDomePermissions;
import java.time.Duration;
import java.util.concurrent.Callable;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PageContextTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockFolder team = j.createFolder("team");
        FreeStyleProject app = team.createProject(FreeStyleProject.class, "my app");
        j.buildAndAssertSuccess(app);
        app.getBuildersList().add(new FailureBuilder());
        j.buildAndAssertStatus(hudson.model.Result.FAILURE, app);
        j.createFreeStyleProject("hidden");
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, MajorDomePermissions.USE).everywhere().to("dev")
                .grant(Item.READ).onItems(team, app).to("dev"));
    }

    private static <T> T asDev(Callable<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById("dev", true).impersonate2())) {
            return body.call();
        }
    }

    @Test
    void resolvesJobAndBuildFromThePath() throws Exception {
        assertEquals("Job on this page: team/my app, build #2 (FAILURE)",
                asDev(() -> PageContext.describe("/jenkins/job/team/job/my%20app/2/console")));
        assertEquals("Job on this page: team/my app, build #1 (SUCCESS)",
                asDev(() -> PageContext.describe("/view/all/job/team/job/my%20app/lastSuccessfulBuild/")));
        assertEquals("Job on this page: team/my app",
                asDev(() -> PageContext.describe("/jenkins/job/team/job/my%20app/configure")));
    }

    @Test
    void ignoresPagesWithoutAJobAndHiddenJobs() throws Exception {
        assertNull(asDev(() -> PageContext.describe("/jenkins/manage/")));
        assertNull(asDev(() -> PageContext.describe("/jenkins/job/hidden/1/")));
        assertNull(asDev(() -> PageContext.describe("")));
    }

    @Test
    void contextReachesTheModel() throws Exception {
        ScriptedProvider model = new ScriptedProvider().text("ok");
        asDev(() -> new ChatService(model, 5, Duration.ofMinutes(10))
                .chat(ConversationStore.get().forUser("dev"), "why did this fail?", "/jenkins/job/team/job/my%20app/2/"));
        var sent = model.requests().get(0).messages();
        String userText = sent.get(sent.size() - 1).getText();
        assertTrue(userText.contains("Job on this page: team/my app, build #2 (FAILURE)"), userText);
    }
}
