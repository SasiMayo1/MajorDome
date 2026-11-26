package io.jenkins.plugins.majordome.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.tasks.junit.JUnitResultArchiver;
import io.jenkins.plugins.majordome.MajorDomeConfiguration;
import io.jenkins.plugins.majordome.util.Json;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FakeChangeLogSCM;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DiagnoseBuildToolTest {

    private JenkinsRule j;
    private FreeStyleProject app;

    /** Build 1 passes; build 2 breaks testA; build 3 also breaks testB and logs a secret. */
    private static final class ScriptedBuild extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws java.io.IOException, InterruptedException {
            int n = build.getNumber();
            listener.getLogger().println("compiling module core");
            if (n >= 3) {
                listener.getLogger().println("deploy token=s3cr3t-value-123");
                listener.getLogger().println("ERROR: cannot find symbol: class FooService");
            }
            String a = n >= 2 ? "<failure message=\"expected 1 but was 2\">trace</failure>" : "";
            String b = n >= 3 ? "<failure message=\"NullPointerException\">trace</failure>" : "";
            build.getWorkspace().child("report.xml").write(
                    "<testsuite name=\"core\" tests=\"3\">"
                            + "<testcase classname=\"core.CalcTest\" name=\"testA\">" + a + "</testcase>"
                            + "<testcase classname=\"core.CalcTest\" name=\"testB\">" + b + "</testcase>"
                            + "<testcase classname=\"core.CalcTest\" name=\"testC\"/>"
                            + "</testsuite>",
                    "UTF-8");
            return true;
        }
    }

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("dev"));
        app = j.createFreeStyleProject("app");
        FakeChangeLogSCM scm = new FakeChangeLogSCM();
        app.setScm(scm);
        app.getBuildersList().add(new ScriptedBuild());
        app.getPublishersList().add(new JUnitResultArchiver("report.xml"));

        j.buildAndAssertSuccess(app);
        scm.addChange().withAuthor("alice").withMsg("Change rounding in Calc");
        j.buildAndAssertStatus(Result.UNSTABLE, app);
        scm.addChange().withAuthor("bob").withMsg("Remove FooService");
        j.buildAndAssertStatus(Result.UNSTABLE, app);
    }

    private static JsonNode diagnose(String args) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById("dev", true).impersonate2())) {
            return Json.parse(new DiagnoseBuildTool().execute(Json.parse(args)));
        }
    }

    @Test
    void gathersEverythingNeededToExplainAFailure() throws Exception {
        JsonNode out = diagnose("{\"job\":\"app\"}");

        assertEquals(3, out.path("build").path("number").asInt());
        assertEquals("UNSTABLE", out.path("build").path("result").asText());
        assertEquals(1, out.path("lastSuccessfulBuild").path("number").asInt());
        assertEquals(2, out.path("unsuccessfulSince").asInt());
        assertEquals(2, out.path("unsuccessfulBuildsInARow").asInt());

        // Both commits since the last success, newest build first.
        JsonNode changes = out.path("changesSinceLastSuccess");
        assertEquals(2, changes.size());
        assertEquals("bob", changes.path(0).path("author").asText());
        assertEquals(3, changes.path(0).path("build").asInt());
        assertEquals("alice", changes.path(1).path("author").asText());

        // testB broke in this build; testA was already failing.
        JsonNode tests = out.path("tests");
        assertEquals(2, tests.path("failed").asInt());
        assertEquals("core.CalcTest.testB", tests.path("newFailures").path(0).path("name").asText());
        assertEquals("core.CalcTest.testA", tests.path("alreadyFailing").path(0).path("name").asText());
        assertEquals(2, tests.path("alreadyFailing").path(0).path("failingForBuilds").asInt());

        String errors = out.path("logErrorLines").asText();
        assertTrue(errors.contains("cannot find symbol: class FooService"), errors);
        assertFalse(errors.contains("compiling module core"), errors);
        assertFalse(out.toString().contains("s3cr3t-value-123"), "secrets are redacted");
    }

    @Test
    void olderBuildComparesWithTheSuccessBeforeIt() throws Exception {
        JsonNode out = diagnose("{\"job\":\"app\",\"number\":2}");
        assertEquals(1, out.path("lastSuccessfulBuild").path("number").asInt());
        assertEquals(1, out.path("changesSinceLastSuccess").size());
        assertEquals("core.CalcTest.testA", out.path("tests").path("newFailures").path(0).path("name").asText());
    }

    @Test
    void respectsTheBuildLogSwitch() throws Exception {
        MajorDomeConfiguration.get().setAllowBuildLogs(false);
        JsonNode out = diagnose("{\"job\":\"app\"}");
        assertTrue(out.path("log").asText().contains("disabled"));
        assertTrue(out.path("logErrorLines").isMissingNode());
        assertFalse(out.toString().contains("cannot find symbol"), "no log content at all");
    }
}
