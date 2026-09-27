# How to use the Anthropic plugin

Call Claude models from Kestra flows for text generation, summarization, classification, and other language tasks.

## Authentication

Set `apiKey` to your Anthropic API key. Store it in a [secret](https://kestra.io/docs/concepts/secret).

## Tasks

`ChatCompletion` sends a prompt to a Claude model and returns the response. Set `model` to choose the model (e.g., `claude-opus-4-7`, `claude-sonnet-4-6`) and `maxTokens` to cap the response length. It returns the completion text, any tool uses, the stop reason, and cache-token counts as outputs, and emits input/output/cache token-usage metrics.

`MessageBatch` creates, retrieves, or cancels an Anthropic Message Batch. Set `mode` to `CREATE` and pass `requests` (each with a unique `customId`, a model, and messages) to start a batch and return `batchId`. Set `mode` to `RETRIEVE` with that `batchId` to read `status` and `requestCounts`; once `status` is `ended`, the same call also returns `resultsUrl` and per-request `results`. Repeat `RETRIEVE` from a `LoopUntil` task until the batch ends. `CANCEL` stops a batch that is still processing. Batches run asynchronously (at most 24 hours) and are billed at half the real-time Messages API rate. `RETRIEVE` emits the same token-usage metrics as `ChatCompletion` for succeeded requests.

`ListModels` lists the Claude models available to your API key.

The Files API tasks manage files for use with Claude, all via Anthropic's beta Files endpoint: `UploadFile` uploads a file from Kestra internal storage, `GetFile` retrieves a file's metadata, `ListFiles` lists stored files, and `DeleteFile` removes one.
