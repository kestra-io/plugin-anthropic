package io.kestra.plugin.anthropic;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.agents.AgentCreateParams;
import com.anthropic.models.beta.agents.BetaManagedAgentsModel;
import com.anthropic.models.beta.environments.EnvironmentCreateParams;
import com.anthropic.models.beta.sessions.SessionCreateParams;
import com.anthropic.models.beta.sessions.events.EventSendParams;
import com.fasterxml.jackson.annotation.JsonIgnore;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a Claude Code session on Claude Managed Agents",
    description = "Creates an agent, starts a session in an Anthropic-managed cloud environment, sends the prompt, and follows the session events in the execution logs until the agent stops. Nothing has to be installed or hosted on the Kestra side. The task authenticates with an Anthropic API key only. Killing the execution interrupts the session. See the [Managed Agents documentation](https://platform.claude.com/docs/en/managed-agents/overview)."
)
@Plugin(
    examples = {
        @Example(
            title = "Fix a failing test in a repository, with a spending limit.",
            full = true,
            code = """
                id: claude_code_session
                namespace: company.team

                tasks:
                  - id: session
                    type: io.kestra.plugin.anthropic.ManagedAgentSession
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: claude-sonnet-5
                    systemPrompt: "You are a careful engineer. Make the smallest change that works."
                    prompt: "Investigate the flaky test in the CI logs, fix it, and open a pull request."
                    maxCostUsd: 5
                    repository:
                      url: https://github.com/acme/app
                      branch: main
                      token: "{{ secret('GITHUB_TOKEN') }}"

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.session.status }} ({{ outputs.session.costUsd }} USD): {{ outputs.session.message }}"
                """
        )
    },
    metrics = {
        @Metric(name = "usage.input.tokens", type = Counter.TYPE, unit = "token", description = "Input tokens used by the session."),
        @Metric(name = "usage.output.tokens", type = Counter.TYPE, unit = "token", description = "Output tokens used by the session."),
        @Metric(name = "usage.cache.read.tokens", type = Counter.TYPE, unit = "token", description = "Cache read tokens used by the session.")
    }
)
public class ManagedAgentSession extends AbstractAnthropic implements RunnableTask<ManagedAgentSession.Output> {

    @Schema(title = "Model", description = "Claude model the agent runs on, for example `claude-sonnet-5`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> model;

    @Schema(title = "Prompt", description = "The task the agent works through on its own.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> prompt;

    @Schema(title = "System prompt", description = "Optional system instructions for the agent.")
    @PluginProperty(group = "advanced")
    private Property<String> systemPrompt;

    @Schema(title = "Git repository", description = "Repository mounted in the session sandbox before the agent starts.")
    @PluginProperty(group = "advanced")
    private GitRepository repository;

    @Schema(title = "MCP servers", description = "Remote MCP servers the agent can call.")
    @PluginProperty(group = "advanced")
    private Property<List<McpServer>> mcpServers;

    @Schema(title = "Skills", description = "Skills made available to the agent.")
    @PluginProperty(group = "advanced")
    private Property<List<Skill>> skills;

    @Schema(
        title = "Spending limit in USD",
        description = "The session pauses once its list cost reaches this amount, and the task then ends with status `SPENDING_LIMIT_REACHED`."
    )
    @PluginProperty(group = "advanced")
    private Property<BigDecimal> maxCostUsd;

    @Schema(
        title = "Environment ID",
        description = "Existing environment to run in. When empty, a cloud environment with unrestricted networking is created for the run and archived afterwards."
    )
    @PluginProperty(group = "advanced")
    private Property<String> environmentId;

    @Schema(title = "Timeout", description = "Maximum time to follow the session. The session is interrupted when it is reached.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> timeout = Property.ofValue(Duration.ofHours(1));

    @JsonIgnore
    private transient volatile AnthropicClient activeClient;

    @JsonIgnore
    private transient volatile String activeSessionId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rApiKey = runContext.render(apiKey).as(String.class)
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("apiKey is required"));
        var rModel = runContext.render(model).as(String.class)
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("model is required"));
        var rPrompt = runContext.render(prompt).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("prompt is required"));
        var rSystemPrompt = runContext.render(systemPrompt).as(String.class).orElse(null);
        var rMcpServers = runContext.render(mcpServers).asList(McpServer.class);
        var rSkills = runContext.render(skills).asList(Skill.class);
        var rMaxCost = runContext.render(maxCostUsd).as(BigDecimal.class).orElse(null);
        var rEnvironmentId = runContext.render(environmentId).as(String.class).filter(value -> !value.isBlank()).orElse(null);
        var rTimeout = runContext.render(timeout).as(Duration.class).orElse(Duration.ofHours(1));

        var client = anthropicClient(rApiKey);
        this.activeClient = client;
        String agentId = null;
        String createdEnvironmentId = null;
        try {
            agentId = createAgent(client, runContext, rModel, rSystemPrompt, rMcpServers, rSkills);
            logger.info("Created Managed Agents agent {}", agentId);

            var rEnvId = rEnvironmentId;
            if (rEnvId == null) {
                rEnvId = createEnvironment(client, runContext);
                createdEnvironmentId = rEnvId;
                logger.info("Created Managed Agents environment {}", rEnvId);
            }

            var sessionId = createSession(client, runContext, agentId, rEnvId, rMaxCost);
            this.activeSessionId = sessionId;
            logger.info("Started Managed Agents session {}", sessionId);

            return followSession(client, runContext, sessionId, rPrompt, rTimeout);
        } finally {
            cleanup(client, agentId, createdEnvironmentId, runContext);
            this.activeClient = null;
            this.activeSessionId = null;
            client.close();
        }
    }

    @Override
    public void kill() {
        var client = this.activeClient;
        var sessionId = this.activeSessionId;
        if (client != null && sessionId != null) {
            try {
                interrupt(client, sessionId);
            } catch (Exception ignored) {
                // best effort: the execution is being killed anyway
            }
        }
    }

    protected AnthropicClient anthropicClient(String rApiKey) {
        return buildClient(rApiKey);
    }

    private String createAgent(
        AnthropicClient client,
        RunContext runContext,
        String rModel,
        String rSystemPrompt,
        List<McpServer> rMcpServers,
        List<Skill> rSkills) throws Exception {
        var builder = AgentCreateParams.builder()
            .name("kestra-" + runContext.flowInfo().namespace() + "-" + runContext.flowInfo().id())
            .model(BetaManagedAgentsModel.of(rModel));
        if (rSystemPrompt != null && !rSystemPrompt.isBlank()) {
            builder.system(rSystemPrompt);
        }

        // Typed builders require every discriminator, so lists are sent as JSON bodies, matching the API reference.
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(Map.of("type", "agent_toolset_20260401"));
        if (!rMcpServers.isEmpty()) {
            List<Map<String, Object>> servers = new ArrayList<>();
            for (var server : rMcpServers) {
                var name = runContext.render(server.getName()).as(String.class).orElseThrow();
                var url = runContext.render(server.getUrl()).as(String.class).orElseThrow();
                servers.add(Map.of("type", "url", "name", name, "url", url));
                tools.add(Map.of("type", "mcp_toolset", "mcp_server_name", name));
            }
            builder.putAdditionalBodyProperty("mcp_servers", JsonValue.from(servers));
        }
        builder.putAdditionalBodyProperty("tools", JsonValue.from(tools));

        if (!rSkills.isEmpty()) {
            List<Map<String, Object>> skillList = new ArrayList<>();
            for (var skill : rSkills) {
                var skillId = runContext.render(skill.getSkillId()).as(String.class).orElseThrow();
                var type = skill.getType() == null ? Skill.Type.ANTHROPIC : skill.getType();
                skillList.add(Map.of("type", type.name().toLowerCase(), "skill_id", skillId));
            }
            builder.putAdditionalBodyProperty("skills", JsonValue.from(skillList));
        }

        return client.beta().agents().create(builder.build()).id();
    }

    private String createEnvironment(AnthropicClient client, RunContext runContext) {
        var params = EnvironmentCreateParams.builder()
            .name("kestra-" + runContext.flowInfo().id() + "-" + Instant.now().toEpochMilli())
            .putAdditionalBodyProperty(
                "config",
                JsonValue.from(Map.of("type", "cloud", "networking", Map.of("type", "unrestricted")))
            )
            .build();
        return client.beta().environments().create(params).id();
    }

    private String createSession(
        AnthropicClient client,
        RunContext runContext,
        String agentId,
        String rEnvironmentId,
        BigDecimal rMaxCost) throws Exception {
        var builder = SessionCreateParams.builder()
            .agent(agentId)
            .environmentId(rEnvironmentId)
            .title("Kestra " + runContext.flowInfo().namespace() + "." + runContext.flowInfo().id());

        if (repository != null) {
            var url = runContext.render(repository.getUrl()).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("repository.url is required"));
            var token = runContext.render(repository.getToken()).as(String.class).orElse(null);
            var branch = runContext.render(repository.getBranch()).as(String.class).orElse(null);

            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("type", "github_repository");
            resource.put("url", url);
            if (token != null) {
                resource.put("authorization_token", token);
            }
            if (branch != null && !branch.isBlank()) {
                resource.put("checkout", Map.of("type", "branch", "name", branch));
            }
            builder.putAdditionalBodyProperty("resources", JsonValue.from(List.of(resource)));
        }

        if (rMaxCost != null) {
            builder.putAdditionalBodyProperty(
                "budget",
                JsonValue.from(Map.of("type", "limit", "max_list_cost", Map.of("amount", rMaxCost.toPlainString(), "currency", "USD")))
            );
        }

        return client.beta().sessions().create(builder.build()).id();
    }

    private Output followSession(AnthropicClient client, RunContext runContext, String sessionId, String rPrompt, Duration rTimeout) {
        var logger = runContext.logger();
        var deadline = Instant.now().plus(rTimeout);
        var message = new StringBuilder();
        ClaudeCodeSessionStatus status = null;
        String error = null;

        // Open the stream before sending the prompt so no event is missed.
        try (var stream = client.beta().sessions().events().streamStreaming(sessionId)) {
            client.beta().sessions().events().send(
                EventSendParams.builder()
                    .sessionId(sessionId)
                    .putAdditionalBodyProperty(
                        "events",
                        JsonValue.from(List.of(Map.of("type", "user.message", "content", List.of(Map.of("type", "text", "text", rPrompt)))))
                    )
                    .build()
            );

            var iterator = stream.stream().iterator();
            while (status == null && iterator.hasNext()) {
                var event = iterator.next();

                if (Instant.now().isAfter(deadline)) {
                    interrupt(client, sessionId);
                    status = ClaudeCodeSessionStatus.FAILED;
                    error = "Session timed out after " + rTimeout;
                    break;
                }

                if (event.agentMessage().isPresent()) {
                    // each agent message replaces the previous one: the last one is the final message
                    message.setLength(0);
                    for (var block : event.agentMessage().get().content()) {
                        block.text().ifPresent(text -> message.append(text.text()));
                    }
                    logger.info("[agent] {}", message);
                } else if (event.agentToolUse().isPresent()) {
                    logger.info("[tool] {}", event.agentToolUse().get().name());
                } else if (event.sessionError().isPresent()) {
                    logger.warn("[session error] {}", event.sessionError().get().error());
                } else if (event.sessionStatusTerminated().isPresent()) {
                    status = ClaudeCodeSessionStatus.FAILED;
                    error = "Session terminated";
                } else if (event.sessionStatusIdle().isPresent()) {
                    var stopReason = event.sessionStatusIdle().get().stopReason();
                    if (stopReason.isEndTurn()) {
                        status = ClaudeCodeSessionStatus.FINISHED;
                    } else if (stopReason.isBudgetReached()) {
                        status = ClaudeCodeSessionStatus.SPENDING_LIMIT_REACHED;
                    } else if (stopReason.isRequiresAction()) {
                        status = ClaudeCodeSessionStatus.WAITING_FOR_INPUT;
                    } else {
                        status = ClaudeCodeSessionStatus.FAILED;
                        error = "Session stopped after exhausting its retries";
                    }
                }
            }
        }

        if (status == null) {
            status = ClaudeCodeSessionStatus.FAILED;
            error = "The event stream closed before the session stopped";
        }

        var session = client.beta().sessions().retrieve(sessionId);
        var usage = session.usage();
        usage.inputTokens().ifPresent(tokens -> runContext.metric(Counter.of("usage.input.tokens", tokens)));
        usage.outputTokens().ifPresent(tokens -> runContext.metric(Counter.of("usage.output.tokens", tokens)));
        usage.cacheReadInputTokens().ifPresent(tokens -> runContext.metric(Counter.of("usage.cache.read.tokens", tokens)));
        var cost = usage.listCost().map(amount -> new BigDecimal(amount.amount())).orElse(null);

        logger.info("Session {} ended with status {}", sessionId, status);

        var output = Output.builder()
            .sessionId(sessionId)
            .message(message.isEmpty() ? null : message.toString())
            .status(status)
            .costUsd(cost)
            .build();

        if (status == ClaudeCodeSessionStatus.FAILED) {
            throw new IllegalStateException("Claude Code session " + sessionId + " failed: " + error);
        }
        return output;
    }

    private void interrupt(AnthropicClient client, String sessionId) {
        client.beta().sessions().events().send(
            EventSendParams.builder()
                .sessionId(sessionId)
                .putAdditionalBodyProperty("events", JsonValue.from(List.of(Map.of("type", "user.interrupt"))))
                .build()
        );
    }

    private void cleanup(AnthropicClient client, String agentId, String createdEnvironmentId, RunContext runContext) {
        // The session itself is kept so that it can be inspected with the returned session id.
        try {
            if (agentId != null) {
                client.beta().agents().archive(agentId);
            }
            if (createdEnvironmentId != null) {
                client.beta().environments().archive(createdEnvironmentId);
            }
        } catch (Exception e) {
            runContext.logger().warn("Could not archive the temporary agent or environment: {}", e.getMessage());
        }
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GitRepository {
        @Schema(title = "Repository URL")
        @NotNull
        private Property<String> url;

        @Schema(title = "Branch to check out")
        private Property<String> branch;

        @Schema(title = "Access token", description = "Token used to clone the repository and push changes.")
        @PluginProperty(secret = true)
        private Property<String> token;
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class McpServer {
        @Schema(title = "Server name", description = "Unique name of the MCP server within the agent.")
        @NotNull
        private Property<String> name;

        @Schema(title = "Server URL")
        @NotNull
        private Property<String> url;
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Skill {
        @Schema(title = "Skill type", description = "`ANTHROPIC` for prebuilt skills, `CUSTOM` for skills uploaded to your organization.")
        private Type type;

        @Schema(title = "Skill ID")
        @NotNull
        private Property<String> skillId;

        public enum Type {
            ANTHROPIC,
            CUSTOM
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Session ID", description = "ID of the Managed Agents session, usable to inspect it in the Claude Console.")
        private final String sessionId;

        @Schema(title = "Final message", description = "Last message written by the agent.")
        private final String message;

        @Schema(title = "Status", description = "How the session ended.")
        private final ClaudeCodeSessionStatus status;

        @Schema(title = "Cost in USD", description = "List cost of the session, when reported by the API.")
        private final BigDecimal costUsd;
    }
}
