# How to use the Anthropic plugin

Call Claude models from Kestra flows for text generation, summarization, classification, and other language tasks.

## Authentication

Set `apiKey` to your Anthropic API key. Store it in a [secret](https://kestra.io/docs/concepts/secret).

## Tasks

`ChatCompletion` sends a prompt to a Claude model and returns the response. Set `model` to choose the model (e.g., `claude-opus-4-7`, `claude-sonnet-4-6`) and `maxTokens` to cap the response length. It returns the completion text, any tool uses, the stop reason, and cache-token counts as outputs, and emits input/output/cache token-usage metrics.

Enable `thinking` with `budgetTokens` of at least 1024 and less than `maxTokens` to request extended thinking. The task sets `temperature` to 1 when thinking is enabled. Reasoning returned by Claude is available in `thinking`; redacted reasoning remains in `rawResponse`. Set `stream: true` to log text deltas as they arrive. The final outputs and usage metrics have the same shape as a non-streaming response; an interrupted or incomplete stream fails the task.

Message `content` accepts either the existing plain string or a list of blocks with `type: text`, `image`, or `document`. Image and PDF sources accept `type: url` with `url`, `type: base64` with `data` and `mediaType`, or `type: file` with `fileId` from `UploadFile`. Image media types are `image/jpeg`, `image/png`, `image/gif`, and `image/webp`; PDFs use `application/pdf`. The API spellings `media_type` and `file_id` are also accepted. Pass `betas: [files-api-2025-04-14]` for uploaded file sources. To request document citations, set `citations: {enabled: true}` on the document block. Response references are returned in `citations`, including document indexes and page, character, or block positions.

Use `toolChoice: AUTO` to let Claude decide, `ANY` to require a tool, `TOOL` with `toolChoiceName` to select one, or `NONE` to disable tools. Extended thinking supports `AUTO` and `NONE`. `stopSequences` accepts a list of stop strings; `stopSequence` returns the matched string. Optional `metadata: {userId: workflow-analysis}` attributes requests to a user or workflow, and `serviceTier` accepts `AUTO` or `STANDARD_ONLY`. `betas` also accepts additional Anthropic beta identifiers without a plugin release.

For explicit prompt cache breakpoints, set `cacheControl: ephemeral` on a custom tool, a content block, or a message (applied to its final content block). Use `systemPromptBlocks` with `text` and optional `cacheControl` instead of the string `system` property to cache individual system blocks. These breakpoints can be combined with the existing global `promptCaching` flag, subject to Anthropic's cache breakpoint limits.

All Anthropic tasks accept an optional `baseUrl`, `connectTimeout`, `readTimeout`, and `maxRetries`. Durations use ISO-8601 strings, for example `connectTimeout: PT30S` and `readTimeout: PT5M`. Unset timeouts use SDK defaults; zero disables that timeout. `maxRetries` defaults to 2; set it to 0 to disable SDK retries. The task examples demonstrate thinking, streaming, multimodal sources, caching, attribution, tool choice, and client configuration.

`CountTokens` estimates input tokens for the same `messages`, `system`, `tools`, and `model` you would send to `ChatCompletion`. It calls the token-count endpoint and returns `inputTokens` only. It does not create a message or call the model. A later task can read `{{ outputs.count_tokens.inputTokens }}` and skip `ChatCompletion` when the count is too high.

`MessageBatch` creates, retrieves, or cancels an Anthropic Message Batch. Set `mode` to `CREATE` and pass `requests` (each with a unique `customId`, a model, and messages) to start a batch and return `batchId`. `customId` is letters, digits, `_`, or `-`, 1–64 characters, and is sent unchanged. Set `mode` to `RETRIEVE` with that `batchId` to read `status` and `requestCounts`. Once `status` is `ended`, the same call also returns `resultsUrl`. By default `fetchType` is `STORE`: per-request rows are written to Kestra internal storage as an ION file (`uri`, with `size`) instead of being kept on the task output. Set `fetchType` to `FETCH` to return every row on `results`, `FETCH_ONE` for the first row only, or `NONE` to skip the download. If the results file is no longer available, RETRIEVE still returns the batch status and logs a warning. Repeat `RETRIEVE` from a `LoopUntil` task until the batch ends, and set `retry` on that task for transient download errors. `CANCEL` stops a batch that is still processing. Batches run asynchronously (at most 24 hours) and are billed at half the real-time Messages API rate. `RETRIEVE` emits one total per token-usage metric for the succeeded rows it actually read.

`ListModels` lists the Claude models available to your API key.

The Files API tasks manage files for use with Claude, all via Anthropic's beta Files endpoint: `UploadFile` uploads a file from Kestra internal storage, `GetFile` retrieves a file's metadata, `ListFiles` lists stored files, and `DeleteFile` removes one.

`ManagedAgentSession` runs a Claude Code session on [Claude Managed Agents](https://platform.claude.com/docs/en/managed-agents/overview), so nothing is installed or hosted on the Kestra side. It authenticates with `apiKey` only. Set `model` and `prompt`, and optionally `systemPrompt`, a Git `repository` (`url`, `branch`, `token`), `mcpServers`, `skills` and `maxCostUsd` (a spending limit above 0). The task creates an agent and an environment for the run, follows the session events in the execution logs, and archives the temporary agent and environment at the end. Set `environmentId` to reuse an existing environment; otherwise `networking` sets the access of the created one, and `LIMITED` with `allowedHosts` is the safer choice for untrusted prompts or repositories. It returns `sessionId`, the final `message`, the `status` and `costUsd`. `status` is `FINISHED`, `SPENDING_LIMIT_REACHED` or `WAITING_FOR_INPUT`; any other ending fails the task. Killing the execution, or setting the task `timeout`, interrupts the session.
