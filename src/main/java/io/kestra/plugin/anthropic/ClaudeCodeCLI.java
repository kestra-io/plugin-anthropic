package io.kestra.plugin.anthropic;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.scripts.exec.AbstractExecScript;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    title = "Run the Claude Code CLI in headless mode in a task runner",
    description = "Installs the Claude Code CLI and runs it with `claude -p` inside a task runner (Docker, Kubernetes, cloud batch, process), so the session executes on your own infrastructure and can work on namespace files. Files created or changed in the working directory are returned as output files. Outputs match `ManagedAgentSession`. Authentication uses an Anthropic API key only. Note that Claude Code refuses the `BYPASS_PERMISSIONS` mode when it runs as root, so use a non-root container image for it."
)
@Plugin(
    examples = {
        @Example(
            title = "Update dependencies in namespace files, limited to a few tools and turns.",
            full = true,
            code = """
                id: claude_code_cli
                namespace: company.team

                tasks:
                  - id: update_dependencies
                    type: io.kestra.plugin.anthropic.ClaudeCodeCLI
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    prompt: "Update the outdated dependencies in package.json and run the tests."
                    allowedTools:
                      - Read
                      - Edit
                      - "Bash(npm:*)"
                    permissionMode: ACCEPT_EDITS
                    maxTurns: 20
                    maxCostUsd: 3
                    namespaceFiles:
                      enabled: true
                """
        )
    }
)
public class ClaudeCodeCLI extends AbstractExecScript implements RunnableTask<ClaudeCodeCLI.Output> {
    private static final String RESULT_FILE = "claude-session.jsonl";
    private static final String START_MARKER = ".kestra-claude-start";

    @Builder.Default
    @PluginProperty(group = "execution")
    protected Property<String> containerImage = Property.ofValue("node:22");

    @Schema(title = "Anthropic API key", description = "The only supported authentication; claude.ai subscription credentials must not be used.")
    @NotNull
    @PluginProperty(secret = true, group = "main")
    @ToString.Exclude
    private Property<String> apiKey;

    @Schema(title = "Prompt", description = "The task Claude Code works through on its own.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> prompt;

    @Schema(title = "System prompt", description = "Instructions appended to the default Claude Code system prompt.")
    @PluginProperty(group = "advanced")
    private Property<String> systemPrompt;

    @Schema(title = "Model", description = "Model alias or full name; the CLI default is used when empty.")
    @PluginProperty(group = "advanced")
    private Property<String> model;

    @Schema(title = "Allowed tools", description = "Tools Claude Code may use without asking, such as `Read`, `Edit` or `Bash(git:*)`.")
    @PluginProperty(group = "advanced")
    private Property<List<String>> allowedTools;

    @Schema(title = "Permission mode")
    @PluginProperty(group = "advanced")
    private Property<PermissionMode> permissionMode;

    @Schema(title = "Maximum number of turns")
    @PluginProperty(group = "advanced")
    private Property<Integer> maxTurns;

    @Schema(title = "Spending limit in USD", description = "The session stops once its cost reaches this amount.")
    @PluginProperty(group = "advanced")
    private Property<BigDecimal> maxCostUsd;

    @Schema(title = "Claude Code version", description = "Version of the `@anthropic-ai/claude-code` npm package to install.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> cliVersion = Property.ofValue("latest");

    @Schema(title = "Install the CLI", description = "Set to `false` when the container image already provides the `claude` command.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> installCli = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class)
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("apiKey is required"));
        var rPrompt = runContext.render(prompt).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("prompt is required"));

        Map<String, String> env = new HashMap<>();
        env.put("ANTHROPIC_API_KEY", rApiKey);
        env.put("KESTRA_CLAUDE_PROMPT", rPrompt);

        List<String> args = new ArrayList<>(
            List.of("claude", "-p", "\"$KESTRA_CLAUDE_PROMPT\"", "--output-format", "stream-json", "--verbose")
        );

        var rSystemPrompt = runContext.render(systemPrompt).as(String.class).filter(value -> !value.isBlank());
        if (rSystemPrompt.isPresent()) {
            env.put("KESTRA_CLAUDE_SYSTEM_PROMPT", rSystemPrompt.get());
            args.addAll(List.of("--append-system-prompt", "\"$KESTRA_CLAUDE_SYSTEM_PROMPT\""));
        }
        runContext.render(model).as(String.class).filter(value -> !value.isBlank())
            .ifPresent(value -> args.addAll(List.of("--model", shellQuote(value))));
        var rAllowedTools = runContext.render(allowedTools).asList(String.class);
        if (!rAllowedTools.isEmpty()) {
            args.addAll(List.of("--allowedTools", shellQuote(String.join(",", rAllowedTools))));
        }
        runContext.render(permissionMode).as(PermissionMode.class)
            .ifPresent(value -> args.addAll(List.of("--permission-mode", value.cliValue())));
        runContext.render(maxTurns).as(Integer.class)
            .ifPresent(value -> args.addAll(List.of("--max-turns", String.valueOf(value))));
        runContext.render(maxCostUsd).as(BigDecimal.class)
            .ifPresent(value -> args.addAll(List.of("--max-budget-usd", value.toPlainString())));

        List<String> beforeCommands = new ArrayList<>();
        if (runContext.render(installCli).as(Boolean.class).orElse(true)) {
            var rVersion = runContext.render(cliVersion).as(String.class).orElse("latest");
            beforeCommands.add("npm install -g @anthropic-ai/claude-code@" + shellQuote(rVersion) + " --silent");
        }

        // The CLI exits non-zero on turn or budget limits: the result event decides the status, not the exit code.
        List<String> commands = List.of(
            "set -o pipefail",
            "touch " + START_MARKER,
            "CLAUDE_EXIT=0",
            String.join(" ", args) + " | tee " + RESULT_FILE + " || CLAUDE_EXIT=$?",
            "mkdir -p \"{{ outputDir }}\"",
            "find . -type f -newer " + START_MARKER + " -not -path './.git/*' -not -path '*/node_modules/*' -not -name "
                + START_MARKER + " -not -name " + RESULT_FILE + " -exec cp --parents {} \"{{ outputDir }}\" \\;",
            "echo \"claude exited with code $CLAUDE_EXIT\""
        );

        var outputFilesList = new ArrayList<>(runContext.render(getOutputFiles()).asList(String.class));
        outputFilesList.add(RESULT_FILE);

        var scriptOutput = this.commands(runContext)
            .addEnv(env)
            .withInterpreter(Property.ofValue(List.of("/bin/bash", "-c")))
            .withBeforeCommands(Property.ofValue(beforeCommands))
            .withBeforeCommandsWithOptions(true)
            .withCommands(Property.ofValue(commands))
            .withOutputFiles(outputFilesList)
            .withEnableOutputDirectory(true)
            .run();

        var resultUri = scriptOutput.getOutputFiles().get(RESULT_FILE);
        if (resultUri == null) {
            throw new IllegalStateException("Claude Code did not produce any result (exit code " + scriptOutput.getExitCode() + ")");
        }

        var changedFiles = new HashMap<>(scriptOutput.getOutputFiles());
        changedFiles.remove(RESULT_FILE);

        return parseResult(runContext, resultUri, changedFiles, scriptOutput.getExitCode());
    }

    private Output parseResult(RunContext runContext, URI resultUri, Map<String, URI> files, int exitCode) throws Exception {
        JsonNode result = null;
        try (InputStream in = runContext.storage().getFile(resultUri)) {
            for (var line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || !line.startsWith("{")) {
                    continue;
                }
                var node = JacksonMapper.ofJson().readTree(line);
                if ("result".equals(node.path("type").asText())) {
                    result = node;
                }
            }
        }
        if (result == null) {
            throw new IllegalStateException("Claude Code ended without a result event (exit code " + exitCode + ")");
        }

        var subtype = result.path("subtype").asText("");
        ClaudeCodeSessionStatus status = switch (subtype) {
            case "success" -> result.path("is_error").asBoolean(false) ? ClaudeCodeSessionStatus.FAILED : ClaudeCodeSessionStatus.FINISHED;
            case "error_max_turns" -> ClaudeCodeSessionStatus.MAX_TURNS_REACHED;
            case "error_max_budget_usd" -> ClaudeCodeSessionStatus.SPENDING_LIMIT_REACHED;
            default -> ClaudeCodeSessionStatus.FAILED;
        };

        var output = Output.builder()
            .sessionId(result.path("session_id").asText(null))
            .message(result.hasNonNull("result") ? result.get("result").asText() : null)
            .status(status)
            .costUsd(result.hasNonNull("total_cost_usd") ? result.get("total_cost_usd").decimalValue() : null)
            .numTurns(result.hasNonNull("num_turns") ? result.get("num_turns").asInt() : null)
            .outputFiles(files)
            .build();

        if (status == ClaudeCodeSessionStatus.FAILED) {
            throw new IllegalStateException("Claude Code session " + output.getSessionId() + " failed (" + subtype + "): " + output.getMessage());
        }
        return output;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public enum PermissionMode {
        DEFAULT("default"),
        ACCEPT_EDITS("acceptEdits"),
        PLAN("plan"),
        BYPASS_PERMISSIONS("bypassPermissions");

        private final String cliValue;

        PermissionMode(String cliValue) {
            this.cliValue = cliValue;
        }

        String cliValue() {
            return cliValue;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Session ID", description = "Claude Code session id, usable with `claude --resume`.")
        private final String sessionId;

        @Schema(title = "Final message", description = "Final result returned by Claude Code.")
        private final String message;

        @Schema(title = "Status", description = "How the session ended.")
        private final ClaudeCodeSessionStatus status;

        @Schema(title = "Cost in USD", description = "Total cost of the session reported by the CLI.")
        private final BigDecimal costUsd;

        @Schema(title = "Number of turns")
        private final Integer numTurns;

        @Schema(title = "Changed files", description = "Files created or changed in the working directory, by relative path.")
        @PluginProperty(additionalProperties = URI.class)
        private final Map<String, URI> outputFiles;
    }
}
