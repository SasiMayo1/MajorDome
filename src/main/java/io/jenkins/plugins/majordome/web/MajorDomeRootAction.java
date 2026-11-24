package io.jenkins.plugins.majordome.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.RootAction;
import io.jenkins.plugins.majordome.MajorDomeConfiguration;
import io.jenkins.plugins.majordome.MajorDomePermissions;
import io.jenkins.plugins.majordome.Messages;
import io.jenkins.plugins.majordome.actions.ActionException;
import io.jenkins.plugins.majordome.actions.PendingAction;
import io.jenkins.plugins.majordome.agent.ChatService;
import io.jenkins.plugins.majordome.agent.Conversation;
import io.jenkins.plugins.majordome.agent.ConversationStore;
import io.jenkins.plugins.majordome.agent.TranscriptEntry;
import io.jenkins.plugins.majordome.agent.TurnResult;
import io.jenkins.plugins.majordome.llm.LlmException;
import io.jenkins.plugins.majordome.util.Json;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

/**
 * {@code /majordome}: the full-page chat and the JSON endpoints used by the chat widget. Every request
 * requires MajorDome/Use; POST requests are also protected by the Jenkins CSRF crumb.
 */
@Extension
public class MajorDomeRootAction implements RootAction, StaplerProxy {

    private static final Logger LOGGER = Logger.getLogger(MajorDomeRootAction.class.getName());

    @Override
    public String getIconFileName() {
        return Jenkins.get().hasPermission(MajorDomePermissions.USE) && MajorDomeConfiguration.get().isReady()
                ? "symbol-chatbubbles-outline plugin-ionicons-api"
                : null;
    }

    @Override
    public String getDisplayName() {
        return Messages.MajorDome_DisplayName();
    }

    @Override
    public String getUrlName() {
        return "majordome";
    }

    @Override
    public Object getTarget() {
        Jenkins.get().checkPermission(MajorDomePermissions.USE);
        return this;
    }

    private static Conversation conversation() {
        return ConversationStore.get().forUser(Jenkins.getAuthentication2().getName());
    }

    @POST
    public void doChat(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JsonNode body = readBody(req);
        String message = body.path("message").asText("");
        if (message.isBlank()) {
            error(rsp, HttpServletResponse.SC_BAD_REQUEST, "Empty message");
            return;
        }
        String page = body.path("page").asText(null);
        try {
            TurnResult turn = ChatService.fromConfiguration().chat(conversation(), message, page);
            ObjectNode out = Json.object();
            out.put("reply", turn.reply());
            ArrayNode proposals = out.putArray("proposals");
            for (PendingAction p : turn.proposals()) {
                proposals.addObject()
                        .put("id", p.id())
                        .put("tool", p.toolName())
                        .put("preview", p.preview())
                        .put("expiresAt", p.expiresAt().toString());
            }
            out.putObject("usage").put("inputTokens", turn.inputTokens()).put("outputTokens", turn.outputTokens());
            write(rsp, HttpServletResponse.SC_OK, out);
        } catch (LlmException e) {
            LOGGER.log(Level.FINE, "MajorDome model call failed", e);
            error(rsp, HttpServletResponse.SC_BAD_GATEWAY, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error(rsp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Interrupted");
        }
    }

    @POST
    public void doConfirm(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        String id = readBody(req).path("id").asText("");
        try {
            ChatService.Decision decision = ChatService.confirm(conversation(), id);
            ObjectNode out = Json.object();
            out.put("status", decision.executed() ? "EXECUTED" : "FAILED");
            out.put("message", decision.message());
            write(rsp, HttpServletResponse.SC_OK, out);
        } catch (ActionException e) {
            error(rsp, statusFor(e), e.getMessage());
        }
    }

    @POST
    public void doReject(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        String id = readBody(req).path("id").asText("");
        try {
            ChatService.reject(conversation(), id);
            write(rsp, HttpServletResponse.SC_OK, Json.object().put("status", "REJECTED"));
        } catch (ActionException e) {
            error(rsp, statusFor(e), e.getMessage());
        }
    }

    @POST
    public void doReset(StaplerResponse2 rsp) throws IOException {
        String userId = Jenkins.getAuthentication2().getName();
        ConversationStore.get().reset(userId);
        io.jenkins.plugins.majordome.actions.PendingActionStore.get().discardAllFor(userId);
        write(rsp, HttpServletResponse.SC_OK, Json.object().put("status", "OK"));
    }

    /** The transcript, so the floating widget survives page navigation. Read-only. */
    @GET
    public void doHistory(StaplerResponse2 rsp) throws IOException {
        ObjectNode out = Json.object();
        out.put("ready", MajorDomeConfiguration.get().isReady());
        ArrayNode entries = out.putArray("entries");
        for (TranscriptEntry e : conversation().getTranscript()) {
            ObjectNode o = entries.addObject();
            o.put("kind", e.getKind().name());
            o.put("text", e.getText());
            if (e.getActionId() != null) {
                o.put("id", e.getActionId());
                o.put("tool", e.getToolName());
                TranscriptEntry.Status status = e.getStatus();
                o.put("status", status == null ? null : status.name());
                o.put("result", e.getResult());
            }
        }
        write(rsp, HttpServletResponse.SC_OK, out);
    }

    private static int statusFor(ActionException e) {
        return switch (e.getReason()) {
            case NOT_FOUND -> HttpServletResponse.SC_NOT_FOUND;
            case EXPIRED -> HttpServletResponse.SC_GONE;
            case NOT_YOURS -> HttpServletResponse.SC_FORBIDDEN;
        };
    }

    private static JsonNode readBody(StaplerRequest2 req) throws IOException {
        try {
            JsonNode node = Json.MAPPER.readTree(req.getInputStream());
            return node == null ? Json.object() : node;
        } catch (IOException e) {
            return Json.object();
        }
    }

    private static void write(StaplerResponse2 rsp, int status, JsonNode body) throws IOException {
        rsp.setStatus(status);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-store");
        rsp.getWriter().write(Json.write(body));
    }

    private static void error(StaplerResponse2 rsp, int status, String message) throws IOException {
        write(rsp, status, Json.object().put("error", message));
    }
}
