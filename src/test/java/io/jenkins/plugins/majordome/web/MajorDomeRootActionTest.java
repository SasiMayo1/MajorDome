package io.jenkins.plugins.majordome.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import hudson.model.Item;
import io.jenkins.plugins.majordome.MajorDomeConfiguration;
import io.jenkins.plugins.majordome.MajorDomePermissions;
import io.jenkins.plugins.majordome.agent.ScriptedProvider;
import io.jenkins.plugins.majordome.util.Json;
import java.net.URL;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class MajorDomeRootActionTest {

    private JenkinsRule j;
    private ScriptedProvider model;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, MajorDomePermissions.USE).everywhere().to("dev")
                .grant(Jenkins.READ, Item.READ).everywhere().to("outsider"));
        model = new ScriptedProvider();
        MajorDomeConfiguration.get().setProvider(model);
    }

    private Page post(JenkinsRule.WebClient wc, String path, String json, boolean crumb) throws Exception {
        WebRequest req = new WebRequest(new URL(j.getURL(), path), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(json);
        if (crumb) {
            // Same as the browser UI: crumb as a header, since the body is JSON.
            JsonNode issuer = Json.parse(wc.goTo("crumbIssuer/api/json", "application/json")
                    .getWebResponse().getContentAsString());
            req.setAdditionalHeader(issuer.path("crumbRequestField").asText(), issuer.path("crumb").asText());
        }
        return wc.getPage(req);
    }

    @Test
    void chatEndpointReturnsReply() throws Exception {
        model.text("Hello from the model");
        JenkinsRule.WebClient wc = j.createWebClient().login("dev");

        Page page = post(wc, "majordome/chat", "{\"message\":\"hi\",\"page\":\"/\"}", true);

        assertEquals(200, page.getWebResponse().getStatusCode());
        JsonNode body = Json.parse(page.getWebResponse().getContentAsString());
        assertEquals("Hello from the model", body.path("reply").asText());

        JsonNode history = Json.parse(wc.goTo("majordome/history", "application/json").getWebResponse().getContentAsString());
        assertEquals(2, history.path("entries").size());
    }

    @Test
    void chatStreamSendsEventsThenDone() throws Exception {
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, Item.BUILD, MajorDomePermissions.USE).everywhere().to("dev"));
        j.createFreeStyleProject("app");
        model.streaming()
                .toolCall("trigger_build", "{\"job\":\"app\"}")
                .text("Proposed a build.\nConfirm it below.");
        JenkinsRule.WebClient wc = j.createWebClient().login("dev");

        Page page = post(wc, "majordome/chatStream", "{\"message\":\"build app\",\"page\":\"/\"}", true);

        assertEquals(200, page.getWebResponse().getStatusCode());
        assertTrue(page.getWebResponse().getContentType().startsWith("text/event-stream"));
        String body = page.getWebResponse().getContentAsString();
        int tool = body.indexOf("event: tool\ndata: {\"name\":\"trigger_build\"}");
        int proposal = body.indexOf("event: proposal\n");
        int text = body.indexOf("event: text\ndata: {\"delta\":\"Prop\"}");
        int done = body.indexOf("event: done\n");
        assertTrue(tool >= 0 && proposal > tool && text > proposal && done > text, body);
        // Every data line is a single line of JSON, even when the text contains newlines.
        for (String line : body.split("\n")) {
            if (line.startsWith("data: ")) {
                Json.parse(line.substring("data: ".length()));
            }
        }
        JsonNode result = Json.parse(body.substring(body.indexOf("data: ", done) + 6).split("\n")[0]);
        assertEquals("Proposed a build.\nConfirm it below.", result.path("reply").asText());
        assertEquals(1, result.path("proposals").size());
        assertTrue(j.jenkins.getItemByFullName("app", hudson.model.FreeStyleProject.class).getBuilds().isEmpty());
    }

    @Test
    void chatStreamRejectsBadRequestsBeforeStreaming() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("dev").withThrowExceptionOnFailingStatusCode(false);

        Page empty = post(wc, "majordome/chatStream", "{\"message\":\"  \"}", true);
        assertEquals(400, empty.getWebResponse().getStatusCode());
        assertTrue(empty.getWebResponse().getContentType().startsWith("application/json"));

        Page noCrumb = post(wc, "majordome/chatStream", "{\"message\":\"hi\"}", false);
        assertEquals(403, noCrumb.getWebResponse().getStatusCode());
        assertTrue(model.requests().isEmpty());
    }

    @Test
    void requiresUsePermission() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("outsider").withThrowExceptionOnFailingStatusCode(false);
        Page page = wc.getPage(new URL(j.getURL(), "majordome/history"));
        assertEquals(403, page.getWebResponse().getStatusCode());
    }

    @Test
    void postWithoutCrumbIsRejected() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("dev").withThrowExceptionOnFailingStatusCode(false);
        Page page = post(wc, "majordome/chat", "{\"message\":\"hi\"}", false);
        assertEquals(403, page.getWebResponse().getStatusCode());
        assertTrue(model.requests().isEmpty());
    }
}
