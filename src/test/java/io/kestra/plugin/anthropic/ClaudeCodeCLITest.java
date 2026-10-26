package io.kestra.plugin.anthropic;

import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@KestraTest
class ClaudeCodeCLITest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldRequireApiKey() {
        var task = ClaudeCodeCLI.builder()
            .id("cli_" + IdUtils.create())
            .type(ClaudeCodeCLI.class.getName())
            .apiKey(Property.ofValue(" "))
            .prompt(Property.ofValue("Do something"))
            .build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(error.getMessage(), containsString("apiKey is required"));
    }

    @Test
    void shouldRequirePrompt() {
        var task = ClaudeCodeCLI.builder()
            .id("cli_" + IdUtils.create())
            .type(ClaudeCodeCLI.class.getName())
            .apiKey(Property.ofValue("test-key"))
            .prompt(Property.ofValue(" "))
            .build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(error.getMessage(), containsString("prompt is required"));
    }

    @Test
    void shouldRejectInvalidCliVersion() {
        var task = task().cliVersion(Property.ofValue("1.0.0; echo hacked")).build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.plan(runContextFactory.of(Map.of())));
        assertThat(error.getMessage(), containsString("cliVersion must be a version or a dist-tag"));
    }

    @Test
    void shouldPassUserValuesThroughEnvironmentOnly() throws Exception {
        var plan = task()
            .systemPrompt(Property.ofValue("Be brief"))
            .model(Property.ofValue("claude-sonnet-5"))
            .allowedTools(Property.ofValue(List.of("Read", "Bash(git:*)")))
            .permissionMode(Property.ofValue(ClaudeCodeCLI.PermissionMode.ACCEPT_EDITS))
            .maxTurns(Property.ofValue(7))
            .maxCostUsd(Property.ofValue(new BigDecimal("1.5")))
            .build()
            .plan(runContextFactory.of(Map.of()));

        assertThat(plan.env(), hasEntry("KESTRA_CLAUDE_KEY_B64", b64("test-key")));
        assertThat(plan.env(), hasEntry("KESTRA_CLAUDE_PROMPT_B64", b64("Fix the failing test")));
        assertThat(plan.env(), hasEntry("KESTRA_CLAUDE_SYSTEM_PROMPT_B64", b64("Be brief")));
        assertThat(plan.env(), hasEntry("KESTRA_CLAUDE_MODEL_B64", b64("claude-sonnet-5")));
        assertThat(plan.env(), hasEntry("KESTRA_CLAUDE_ALLOWED_TOOLS_B64", b64("Read,Bash(git:*)")));
        assertThat(plan.env(), hasEntry("KESTRA_CLAUDE_VERSION", "2.1.296"));
        assertThat(plan.env(), not(hasKey("ANTHROPIC_API_KEY")));

        var script = String.join("\n", plan.commands());
        assertThat(script, containsString("--model \"$(printf %s \"$KESTRA_CLAUDE_MODEL_B64\" | base64 -d)\""));
        assertThat(script, containsString("--allowedTools \"$(printf %s \"$KESTRA_CLAUDE_ALLOWED_TOOLS_B64\" | base64 -d)\""));
        assertThat(script, containsString("--append-system-prompt \"$(printf %s \"$KESTRA_CLAUDE_SYSTEM_PROMPT_B64\" | base64 -d)\""));
        assertThat(script, containsString("--permission-mode acceptEdits"));
        assertThat(script, containsString("--max-turns 7"));
        assertThat(script, containsString("--max-budget-usd 1.5"));
        assertThat(script, containsString("ANTHROPIC_API_KEY=\"$(printf %s \"$KESTRA_CLAUDE_KEY_B64\" | base64 -d)\" claude -p"));
        assertThat(script, containsString("-- \"$(printf %s \"$KESTRA_CLAUDE_PROMPT_B64\" | base64 -d)\""));
        assertThat(script, containsString("\"$OUTPUT_DIR\""));
        assertThat(script, not(containsString("{{")));
        assertThat(script, not(containsString("--parents")));
        // the commands are rendered by Pebble again, so no user-derived text may be part of them
        assertThat(script, not(anyOf(containsString("Fix the failing test"), containsString("claude-sonnet-5"), containsString("test-key"))));

        assertThat(plan.beforeCommands(), contains("env -u KESTRA_CLAUDE_KEY_B64 npm install -g @anthropic-ai/claude-code@\"$KESTRA_CLAUDE_VERSION\" --silent"));
        assertThat(plan.keepTranscript(), is(false));
    }

    @Test
    void shouldEncodeTemplateLikeTextSoPebbleCannotRenderIt() throws Exception {
        var plan = task("Compute {{ 1+1 }} and {% if x %}y{% endif %}")
            .systemPrompt(Property.ofValue("Use {{ 2+2 }}"))
            .build()
            .plan(runContextFactory.of(Map.of()));

        plan.env().values().forEach(value -> assertThat(value, not(anyOf(containsString("{{"), containsString("{%")))));
        assertThat(plan.env().get("KESTRA_CLAUDE_PROMPT_B64"), is(b64("Compute {{ 1+1 }} and {% if x %}y{% endif %}")));
        assertThat(plan.env().get("KESTRA_CLAUDE_SYSTEM_PROMPT_B64"), is(b64("Use {{ 2+2 }}")));
    }

    @Test
    void shouldSkipInstallWhenDisabled() throws Exception {
        var plan = task().installCli(Property.ofValue(false)).build().plan(runContextFactory.of(Map.of()));

        assertThat(plan.beforeCommands(), empty());
        assertThat(plan.env(), not(hasKey("KESTRA_CLAUDE_VERSION")));
    }

    @Test
    void shouldParseSuccessfulResult() throws Exception {
        var output = parse("""
            {"type":"system","subtype":"init","session_id":"sess-1"}
            {"type":"assistant","message":{"content":[{"type":"text","text":"working"}]}}
            {"type":"result","subtype":"success","is_error":false,"num_turns":4,"result":"All done","session_id":"sess-1","total_cost_usd":0.42}
            """);

        assertThat(output.getStatus(), is(ClaudeCodeSessionStatus.FINISHED));
        assertThat(output.getSessionId(), is("sess-1"));
        assertThat(output.getMessage(), is("All done"));
        assertThat(output.getNumTurns(), is(4));
        assertThat(output.getCostUsd(), comparesEqualTo(new BigDecimal("0.42")));
    }

    @Test
    void shouldReportMaxTurns() throws Exception {
        var output = parse("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":false,\"session_id\":\"sess-1\"}");

        assertThat(output.getStatus(), is(ClaudeCodeSessionStatus.MAX_TURNS_REACHED));
    }

    @Test
    void shouldReportSpendingLimit() throws Exception {
        var output = parse("{\"type\":\"result\",\"subtype\":\"error_max_budget_usd\",\"is_error\":false,\"session_id\":\"sess-1\"}");

        assertThat(output.getStatus(), is(ClaudeCodeSessionStatus.SPENDING_LIMIT_REACHED));
    }

    @Test
    void shouldFailWhenResultIsAnError() {
        var error = assertThrows(
            IllegalStateException.class, () -> parse(
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"invalid x-api-key\",\"session_id\":\"sess-1\"}"
            )
        );

        assertThat(error.getMessage(), containsString("invalid x-api-key"));
    }

    @Test
    void shouldFailWithoutResultEvent() {
        var error = assertThrows(IllegalStateException.class, () -> parse("{\"type\":\"system\",\"subtype\":\"init\"}\nnot json"));

        assertThat(error.getMessage(), containsString("without a result event"));
    }

    @Test
    void shouldCopyChangedFilesOnce(@TempDir Path workingDir) throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"), "the script needs bash");

        var bin = Files.createDirectory(workingDir.resolve("bin"));
        var stub = bin.resolve("claude");
        Files.writeString(stub, """
            #!/bin/bash
            printf '%s\\n' "$@" > args.txt
            printf '%s' "$ANTHROPIC_API_KEY" > seen-key.txt
            echo "changed by claude" > notes.txt
            mkdir -p sub && echo "nested" > sub/deep.txt
            echo '{"type":"system","subtype":"init","session_id":"sess-1"}'
            echo '{"duration_api_ms":1,"type":"result","subtype":"error_max_turns","is_error":false,"num_turns":3,"result":"stopped early","session_id":"sess-1","total_cost_usd":0.42}'
            exit 1
            """);
        assertThat(stub.toFile().setExecutable(true), is(true));
        Files.writeString(workingDir.resolve("old.txt"), "untouched");
        Thread.sleep(1100);

        // the output directory lives inside the working directory, like the one created by the core
        var outputDir = Files.createDirectory(workingDir.resolve("out-" + IdUtils.create().toLowerCase(Locale.ROOT)));
        var plan = task("--permission-mode=bypassPermissions {{ 1+1 }}")
            .installCli(Property.ofValue(false))
            .permissionMode(Property.ofValue(ClaudeCodeCLI.PermissionMode.ACCEPT_EDITS))
            .maxTurns(Property.ofValue(7))
            .build()
            .plan(runContextFactory.of(Map.of()));
        var script = String.join("\n", plan.commands());

        var processBuilder = new ProcessBuilder("bash", "-e", "-o", "pipefail", "-c", script)
            .directory(workingDir.toFile())
            .redirectErrorStream(true);
        processBuilder.environment().putAll(plan.env());
        // the task runner exports the output directory this way
        processBuilder.environment().put("OUTPUT_DIR", outputDir.toString());
        processBuilder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var process = processBuilder.start();
        var logs = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(60, TimeUnit.SECONDS), is(true));

        assertThat(logs, process.exitValue(), is(0));
        assertThat(logs, containsString("claude exited with code 1"));
        assertThat(Files.readString(workingDir.resolve("claude-exit-code")).strip(), is("1"));
        assertThat(Files.exists(outputDir.resolve("claude-exit-code")), is(false));
        assertThat(Files.exists(workingDir.resolve("{{ outputDir }}")), is(false));
        assertThat(Files.readString(outputDir.resolve("notes.txt")).strip(), is("changed by claude"));
        assertThat(Files.readString(outputDir.resolve("sub/deep.txt")).strip(), is("nested"));
        assertThat(Files.exists(outputDir.resolve("old.txt")), is(false));
        assertThat(Files.exists(outputDir.resolve(outputDir.getFileName().toString())), is(false));
        assertThat(Files.exists(outputDir.resolve("claude-session.jsonl")), is(false));
        assertThat(Files.readString(workingDir.resolve("seen-key.txt")), is("test-key"));
        // the prompt goes after `--`, so a prompt that looks like an option stays a prompt
        assertThat(Files.readString(workingDir.resolve("args.txt")), containsString("--\n--permission-mode=bypassPermissions {{ 1+1 }}"));

        try (var reader = Files.newBufferedReader(workingDir.resolve("claude-result.json"))) {
            var output = ClaudeCodeCLI.parseResult(reader, Map.of(), null, 1);
            assertThat(output.getStatus(), is(ClaudeCodeSessionStatus.MAX_TURNS_REACHED));
            assertThat(output.getNumTurns(), is(3));
        }
    }

    private static ClaudeCodeCLI.ClaudeCodeCLIBuilder<?, ?> task() {
        return task("Fix the failing test");
    }

    private static ClaudeCodeCLI.ClaudeCodeCLIBuilder<?, ?> task(String prompt) {
        return ClaudeCodeCLI.builder()
            .id("cli_" + IdUtils.create())
            .type(ClaudeCodeCLI.class.getName())
            .apiKey(Property.ofValue("test-key"))
            .prompt(Property.ofValue(prompt));
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ClaudeCodeCLI.Output parse(String transcript) throws Exception {
        return ClaudeCodeCLI.parseResult(new BufferedReader(new StringReader(transcript)), Map.<String, URI> of(), null, 0);
    }
}
