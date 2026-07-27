# How to use the Anthropic plugin

Call Claude models from Kestra flows for text generation, summarization, classification, and other language tasks.

## Authentication

Set `apiKey` to your Anthropic API key. Store it in a [secret](https://kestra.io/docs/concepts/secret).

## Tasks

`ChatCompletion` sends a prompt to a Claude model and returns the response. Set `model` to choose the model (e.g., `claude-opus-4-7`, `claude-sonnet-4-6`) and `maxTokens` to cap the response length. It returns the completion text, any tool uses, the stop reason, and cache-token counts as outputs, and emits input/output/cache token-usage metrics.

`ListModels` lists the Claude models available to your API key.

The Files API tasks manage files for use with Claude, all via Anthropic's beta Files endpoint: `UploadFile` uploads a file from Kestra internal storage, `GetFile` retrieves a file's metadata, `ListFiles` lists stored files, and `DeleteFile` removes one.
