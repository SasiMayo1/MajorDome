package io.jenkins.plugins.majordome.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.List;
import java.util.concurrent.Callable;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LookupTest {

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.createFreeStyleProject("demo-fail");
        MockFolder team = j.createFolder("team");
        team.createProject(hudson.model.FreeStyleProject.class, "backend");
        j.createFreeStyleProject("demo-secret");
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("dev")
                .grant(Item.READ).onItems(j.jenkins.getItem("demo-fail"), team, team.getItem("backend")).to("dev"));
    }

    private static <T> T asDev(Callable<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById("dev", true).impersonate2())) {
            return body.call();
        }
    }

    @Test
    void suggestsTheJobTheModelMeant() throws Exception {
        // The exact mistake qwen2.5:3b made in the phase 0 test.
        ToolException e = assertThrows(ToolException.class, () -> asDev(() -> Lookup.job("demo")));
        assertTrue(e.getMessage().contains("Did you mean: 'demo-fail'"), e.getMessage());
    }

    @Test
    void suggestsFoldersAndTypos() throws Exception {
        assertEquals(List.of("team/backend"), asDev(() -> Lookup.similarJobNames("backnd", 3)));
        assertEquals(List.of("team/backend"), asDev(() -> Lookup.similarJobNames("team/Backend-", 3)));
    }

    @Test
    void neverSuggestsHiddenJobs() throws Exception {
        List<String> similar = asDev(() -> Lookup.similarJobNames("demo-secre", 5));
        assertFalse(similar.contains("demo-secret"), similar.toString());
    }

    @Test
    void noSuggestionFallsBackToListJobs() throws Exception {
        ToolException e = assertThrows(ToolException.class, () -> asDev(() -> Lookup.job("zzzzzzzz")));
        assertTrue(e.getMessage().contains("Use list_jobs"), e.getMessage());
    }

    @Test
    void editDistanceStopsAtTheLimit() {
        assertEquals(1, Lookup.editDistance("backnd", "backend", 2));
        assertEquals(3, Lookup.editDistance("abcdef", "uvwxyz", 2));
    }
}
