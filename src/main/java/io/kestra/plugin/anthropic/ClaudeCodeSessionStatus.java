package io.kestra.plugin.anthropic;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(title = "How a Claude Code session ended")
public enum ClaudeCodeSessionStatus {
    @Schema(title = "The agent completed its turn and returned a final message.")
    FINISHED,
    @Schema(title = "The session stopped because it reached the configured spending limit.")
    SPENDING_LIMIT_REACHED,
    @Schema(title = "The session is paused and waits for an input that a flow cannot provide, such as a tool confirmation.")
    WAITING_FOR_INPUT,
    @Schema(title = "The agent stopped because it reached the configured maximum number of turns.")
    MAX_TURNS_REACHED,
    @Schema(title = "The session ended with an error.")
    FAILED
}
