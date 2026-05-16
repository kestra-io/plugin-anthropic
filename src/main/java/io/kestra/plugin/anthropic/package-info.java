@PluginSubGroup(
    title = "Anthropic plugin",
    description = "Tasks that call Anthropic Claude models for chat completions and manage the Anthropic Files API. Configure an Anthropic API key, pick the Claude model to run, tune generation controls like temperature and max tokens, upload and manage files, and capture token usage metrics for your executions.",
    categories = {
        PluginSubGroup.PluginCategory.AI
    }
)
package io.kestra.plugin.anthropic;

import io.kestra.core.models.annotations.PluginSubGroup;
