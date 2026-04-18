# Kestra Anthropic Plugin

## What

- Provides plugin components under `io.kestra.plugin.anthropic`.
- Includes classes such as `ChatCompletion`.

## Why

- This plugin integrates Kestra with Anthropic.
- It provides tasks that call Anthropic Claude models for chat completions.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `anthropic`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.anthropic.ChatCompletion`

### Project Structure

```
plugin-anthropic/
├── src/main/java/io/kestra/plugin/anthropic/
├── src/test/java/io/kestra/plugin/anthropic/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
