package io.kestra.plugin.anthropic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
    description = """
        Installs the Claude Code CLI and runs it with `claude -p` inside a task runner (Docker, Kubernetes, cloud batch, process), so the session executes on your own infrastructure and can work on namespace files.

        Files created or changed in the working directory are returned as output files. Outputs match `ManagedAgentSession`. Authentication uses an Anthropic API key only.

        Claude Code refuses the `BYPASS_PERMISSIONS` mode when it runs as root, so use a non-root container image for it.
        """
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
    private static final String TRANSCRIPT_FILE = "claude-session.jsonl";
    private static final String RESULT_FILE = "claude-result.json";
    private static final String CHANGED_FILES_LIST = ".kestra-claude-changed";
    private static final String START_MARKER = ".kestra-claude-start";
    private static final String EXIT_CODE_FILE = "claude-exit-code";
    private static final Pattern CLI_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    @Builder.Default
    @PluginProperty(group = "execution")
    protected Property<String> containerImage = Property.ofValue("node:22");

    @Schema(title = "Anthropic API key", description = "The only supported authentication; claude.ai subscription credentials must not be used. The key is kept away from the CLI install step, but any `beforeCommands` you add can still read it.")
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

    @Schema(
        title = "Permission mode",
        description = """
            How Claude Code handles actions that need permission. The CLI default is used when empty.

            `BYPASS_PERMISSIONS` lets the session run any command with the task runner's privileges and the API key in reach, and the working directory can hold content an attacker influences (namespace files, repository text, prompt injection). Prefer `ACCEPT_EDITS` with `allowedTools`, and when you need `BYPASS_PERMISSIONS`, use an isolated Docker or Kubernetes runner with a non-root image, no mounted secrets and restricted network access.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<PermissionMode> permissionMode;

    @Schema(title = "Maximum number of turns")
    @PluginProperty(group = "advanced")
    private Property<Integer> maxTurns;

    @Schema(title = "Spending limit in USD", description = "The session stops once its cost reaches this amount.")
    @PluginProperty(group = "advanced")
    private Property<BigDecimal> maxCostUsd;

    @Schema(
        title = "Claude Code version",
        description = "Version or dist-tag of the `@anthropic-ai/claude-code` npm package to install. It is pinned by default so that runs are reproducible."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> cliVersion = Property.ofValue("2.1.296");

    @Schema(title = "Install the CLI", description = "Set to `false` when the container image already provides the `claude` command.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> installCli = Property.ofValue(true);

    @Schema(
        title = "Keep the transcript",
        description = """
            Return the full session transcript (`claude-session.jsonl`) as the `transcript` output.

            It lists every tool call and tool result, so it can contain file contents and command output, including secrets. It is not logged, and it is not stored unless you enable this.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> keepTranscript = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var plan = plan(runContext);

        var outputFilesList = new ArrayList<>(runContext.render(getOutputFiles()).asList(String.class));
        outputFilesList.add(RESULT_FILE);
        outputFilesList.add(EXIT_CODE_FILE);
        if (plan.keepTranscript()) {
            outputFilesList.add(TRANSCRIPT_FILE);
        }

        var scriptOutput = this.commands(runContext)
            .addEnv(plan.env())
            .withInterpreter(Property.ofValue(List.of("/bin/bash", "-c")))
            .withBeforeCommands(Property.ofValue(plan.beforeCommands()))
            .withBeforeCommandsWithOptions(true)
            .withCommands(Property.ofValue(plan.commands()))
            .withOutputFiles(outputFilesList)
            .withEnableOutputDirectory(true)
            .run();

        var files = new HashMap<>(scriptOutput.getOutputFiles());
        var resultUri = files.remove(RESULT_FILE);
        var transcriptUri = files.remove(TRANSCRIPT_FILE);
        // the script itself always ends with exit code 0: the real exit code of claude is kept in a file
        var claudeExitCode = readExitCode(runContext, files.remove(EXIT_CODE_FILE), scriptOutput.getExitCode());
        if (resultUri == null) {
            throw new IllegalStateException("Claude Code did not produce any result (exit code " + claudeExitCode + ")");
        }

        Output output;
        try (var reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(resultUri), StandardCharsets.UTF_8))) {
            output = parseResult(reader, files, transcriptUri, claudeExitCode);
        }

        runContext.logger().info(
            "Claude Code session {} ended with status {} after {} turns ({} USD)",
            output.getSessionId(),
            output.getStatus(),
            output.getNumTurns(),
            output.getCostUsd()
        );
        return output;
    }

    private static int readExitCode(RunContext runContext, URI uri, int fallback) {
        if (uri == null) {
            return fallback;
        }
        try (var reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(uri), StandardCharsets.UTF_8))) {
            var line = reader.readLine();
            return line == null ? fallback : Integer.parseInt(line.strip());
        } catch (IOException | NumberFormatException e) {
            return fallback;
        }
    }

    // Environment values are rendered by Pebble again before the script runs, so user text travels as base64 and is decoded by the shell.
    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String variable) {
        return "\"$(printf %s \"$" + variable + "\" | base64 -d)\"";
    }

    Plan plan(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class)
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("apiKey is required"));
        var rPrompt = runContext.render(prompt).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("prompt is required"));

        // User-derived text only travels base64-encoded in environment variables: Pebble renders both the commands and the environment again
        var env = new HashMap<String, String>();
        env.put("KESTRA_CLAUDE_KEY_B64", encode(rApiKey));
        env.put("KESTRA_CLAUDE_PROMPT_B64", encode(rPrompt));

        var args = new ArrayList<>(List.of("claude", "-p", "--output-format", "stream-json", "--verbose"));

        var rSystemPrompt = runContext.render(systemPrompt).as(String.class).filter(value -> !value.isBlank());
        if (rSystemPrompt.isPresent()) {
            env.put("KESTRA_CLAUDE_SYSTEM_PROMPT_B64", encode(rSystemPrompt.get()));
            args.addAll(List.of("--append-system-prompt", decoded("KESTRA_CLAUDE_SYSTEM_PROMPT_B64")));
        }
        var rModel = runContext.render(model).as(String.class).filter(value -> !value.isBlank());
        if (rModel.isPresent()) {
            env.put("KESTRA_CLAUDE_MODEL_B64", encode(rModel.get()));
            args.addAll(List.of("--model", decoded("KESTRA_CLAUDE_MODEL_B64")));
        }
        var rAllowedTools = runContext.render(allowedTools).asList(String.class);
        if (!rAllowedTools.isEmpty()) {
            env.put("KESTRA_CLAUDE_ALLOWED_TOOLS_B64", encode(String.join(",", rAllowedTools)));
            args.addAll(List.of("--allowedTools", decoded("KESTRA_CLAUDE_ALLOWED_TOOLS_B64")));
        }
        runContext.render(permissionMode).as(PermissionMode.class)
            .ifPresent(value -> args.addAll(List.of("--permission-mode", value.cliValue())));
        runContext.render(maxTurns).as(Integer.class)
            .ifPresent(value -> args.addAll(List.of("--max-turns", String.valueOf(value))));
        runContext.render(maxCostUsd).as(BigDecimal.class)
            .ifPresent(value -> args.addAll(List.of("--max-budget-usd", value.toPlainString())));

        var beforeCommands = new ArrayList<>(runContext.render(getBeforeCommands()).asList(String.class));
        if (runContext.render(installCli).as(Boolean.class).orElse(true)) {
            var rVersion = runContext.render(cliVersion).as(String.class).orElse("");
            if (!CLI_VERSION.matcher(rVersion).matches()) {
                throw new IllegalArgumentException("cliVersion must be a version or a dist-tag, such as 2.1.296 or latest");
            }
            env.put("KESTRA_CLAUDE_VERSION", rVersion);
            // the install step and its dependency scripts must not see the API key
            beforeCommands.add("env -u KESTRA_CLAUDE_KEY_B64 npm install -g @anthropic-ai/claude-code@\"$KESTRA_CLAUDE_VERSION\" --silent");
        }

        // The CLI exits non-zero on turn or budget limits: the result event decides the status, not the exit code.
        // The transcript goes to a file only, and the key is scoped to the claude invocation, not to the install step.
        var commands = List.of(
            "touch " + START_MARKER,
            "CLAUDE_EXIT=0",
            "ANTHROPIC_API_KEY=" + decoded("KESTRA_CLAUDE_KEY_B64") + " " + String.join(" ", args) + " -- " + decoded("KESTRA_CLAUDE_PROMPT_B64")
                + " > " + TRANSCRIPT_FILE + " < /dev/null || CLAUDE_EXIT=$?",
            "grep '\"type\":\"result\"' " + TRANSCRIPT_FILE + " | tail -n 1 > " + RESULT_FILE + " || true",
            // the runner exports the output directory as $OUTPUT_DIR; it lives inside the working directory, so it is skipped by name
            "OUT_NAME=$(basename \"$OUTPUT_DIR\")",
            // the list is complete before anything is copied, so the output directory is never rescanned
            "find . -type f -newer " + START_MARKER + " -not -path './.git/*' -not -path '*/node_modules/*' -not -path \"*/$OUT_NAME/*\" -not -name "
                + START_MARKER + " -not -name " + TRANSCRIPT_FILE + " -not -name " + RESULT_FILE + " -not -name " + EXIT_CODE_FILE + " -not -name "
                + CHANGED_FILES_LIST + " > " + CHANGED_FILES_LIST,
            "mkdir -p \"$OUTPUT_DIR\"",
            // portable copy: `cp --parents` is GNU only
            "while IFS= read -r changed; do changed=\"${changed#./}\"; mkdir -p \"$OUTPUT_DIR/$(dirname \"$changed\")\" && cp \"$changed\" \"$OUTPUT_DIR/$changed\"; done < "
                + CHANGED_FILES_LIST,
            "echo \"$CLAUDE_EXIT\" > " + EXIT_CODE_FILE,
            "echo \"claude exited with code $CLAUDE_EXIT\""
        );

        return new Plan(env, beforeCommands, commands, runContext.render(keepTranscript).as(Boolean.class).orElse(false));
    }

    static Output parseResult(BufferedReader reader, Map<String, URI> files, URI transcript, int exitCode) throws IOException {
        JsonNode result = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("{")) {
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
        var status = switch (subtype) {
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
            .transcript(transcript)
            .build();

        if (status == ClaudeCodeSessionStatus.FAILED) {
            throw new IllegalStateException("Claude Code session " + output.getSessionId() + " failed (" + subtype + "): " + output.getMessage());
        }
        return output;
    }

    record Plan(Map<String, String> env, List<String> beforeCommands, List<String> commands, boolean keepTranscript) {
    }

    public enum PermissionMode {
        ACCEPT_EDITS("acceptEdits"),
        PLAN("plan"),
        DONT_ASK("dontAsk"),
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

        @Schema(title = "Transcript", description = "Full session transcript, only when `keepTranscript` is enabled.")
        private final URI transcript;
    }
}
