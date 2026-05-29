# How to use the Anthropic plugin

Call Claude models from Kestra flows for text generation, summarization, classification, and other language tasks.

## Authentication

Set `apiKey` to your Anthropic API key. Store it in a [secret](https://kestra.io/docs/concepts/secret).

## Tasks

`ChatCompletion` sends a prompt to a Claude model and returns the response. Set `modelId` to choose the model (e.g., `claude-opus-4-7`, `claude-sonnet-4-6`), and `maxTokens` to cap the response length. The task returns the completion text and token usage metrics as outputs for downstream steps.
