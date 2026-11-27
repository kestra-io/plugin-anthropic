package io.kestra.plugin.anthropic;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.Timeout;
import com.anthropic.core.http.ProxyAuthenticator;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.client.configurations.ProxyConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAnthropic extends Task {
    // Tool.InputSchema.Builder already sets type, properties and required, so forwarding them again would serialize them twice.
    private static final Set<String> TYPED_SCHEMA_KEYS = Set.of("type", "properties", "required");

    @Schema(title = "Anthropic API Key")
    @NotNull
    @ToString.Exclude
    @PluginProperty(secret = true, group = "main")
    protected Property<String> apiKey;

    @Schema(
        title = "Base URL",
        description = "Optional Anthropic-compatible endpoint. Defaults to the SDK endpoint. Requests, including the API key, are sent to this host, so set it with `forced: true` in plugin defaults when the API key comes from there."
    )
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl;

    @Schema(title = "Connect timeout", description = "Connection timeout as an ISO-8601 duration. Defaults to PT1M, and zero disables it.")
    @PluginProperty(group = "connection")
    protected Property<Duration> connectTimeout;

    @Schema(
        title = "Read timeout",
        description = "Maximum time for a response as an ISO-8601 duration, both between packets and for the whole call. Defaults to the SDK timeout (PT10M), and zero disables it."
    )
    @PluginProperty(group = "connection")
    protected Property<Duration> readTimeout;

    @Schema(title = "Maximum retries", description = "SDK retries for transient errors, between 0 and 10. Defaults to 2, and 0 disables them.")
    @PluginProperty(group = "reliability")
    @Builder.Default
    protected Property<@Min(0) @Max(10) Integer> maxRetries = Property.ofValue(2);

    @Schema(
        title = "Proxy",
        description = """
            Optional proxy used to reach the Anthropic API. It is applied only when `proxy.address` is set; otherwise the JVM proxy settings are used.
            `proxy.port` is required with an address. `proxy.type` accepts `HTTP` or `SOCKS`, and an omitted or `DIRECT` type is treated as `HTTP`.
            `proxy.username` and `proxy.password` send Basic authentication to an `HTTP` proxy; they are rejected with `SOCKS`, which cannot authenticate here.
            An explicit proxy takes precedence over the JVM proxy flags.
            Rejected credentials are retried up to `maxRetries` times, so set `maxRetries: 0` if the proxy locks accounts after failed logins."""
    )
    @PluginProperty(group = "connection")
    protected ProxyConfiguration proxy;

    protected AnthropicClient buildClient(RunContext runContext, String rApiKey) throws IllegalVariableEvaluationException {
        var builder = AnthropicOkHttpClient.builder().apiKey(rApiKey);
        runContext.render(baseUrl).as(String.class).ifPresent(builder::baseUrl);
        var rConnectTimeout = runContext.render(connectTimeout).as(Duration.class);
        var rReadTimeout = runContext.render(readTimeout).as(Duration.class);
        if (rConnectTimeout.filter(Duration::isNegative).isPresent()) {
            throw new IllegalArgumentException("connectTimeout must be zero or a positive duration such as PT30S.");
        }
        if (rReadTimeout.filter(Duration::isNegative).isPresent()) {
            throw new IllegalArgumentException("readTimeout must be zero or a positive duration such as PT5M.");
        }
        applyProxy(runContext, builder);
        var rMaxRetries = runContext.render(maxRetries).as(Integer.class).orElse(2);
        builder.maxRetries(rMaxRetries);
        if (rConnectTimeout.isPresent() || rReadTimeout.isPresent()) {
            var timeout = Timeout.builder();
            rConnectTimeout.ifPresent(timeout::connect);
            // the SDK caps every call at its request timeout (PT10M), so a longer read timeout must raise it too
            rReadTimeout.ifPresent(value -> timeout.read(value).request(value));
            builder.timeout(timeout.build());
        }
        return builder.build();
    }

    private void applyProxy(RunContext runContext, AnthropicOkHttpClient.Builder builder) throws IllegalVariableEvaluationException {
        if (proxy == null) {
            return;
        }
        var rAddress = runContext.render(proxy.getAddress()).as(String.class).filter(value -> !value.isBlank());
        if (rAddress.isEmpty()) {
            return;
        }
        var rPort = runContext.render(proxy.getPort()).as(Integer.class)
            .orElseThrow(() -> new IllegalArgumentException("A proxy port is required when a proxy address is set (proxy.port)."));
        if (rPort < 1 || rPort > 65535) {
            throw new IllegalArgumentException("The proxy port must be between 1 and 65535 (proxy.port), got " + rPort + ".");
        }
        var rType = runContext.render(proxy.getType()).as(Proxy.Type.class)
            .filter(value -> value != Proxy.Type.DIRECT)
            .orElse(Proxy.Type.HTTP);
        var rUsername = runContext.render(proxy.getUsername()).as(String.class).filter(value -> !value.isEmpty());
        var rPassword = runContext.render(proxy.getPassword()).as(String.class).filter(value -> !value.isEmpty());

        if (rType == Proxy.Type.SOCKS && (rUsername.isPresent() || rPassword.isPresent())) {
            throw new IllegalArgumentException("Proxy authentication is not supported with a SOCKS proxy (proxy.type); remove proxy.username and proxy.password or use an HTTP proxy.");
        }

        runContext.logger().debug("Using {} proxy {}:{}", rType, rAddress.get(), rPort);
        builder.proxy(new Proxy(rType, InetSocketAddress.createUnresolved(rAddress.get(), rPort)));

        if (rUsername.isPresent()) {
            builder.proxyAuthenticator(basicProxyAuthenticator(runContext.logger(), rUsername.get(), rPassword.orElse("")));
        } else if (rPassword.isPresent()) {
            runContext.logger().warn("proxy.password is set without proxy.username, so the proxy credentials are ignored.");
        }
    }

    // ProxyAuthenticator.basic() re-adds the header on every challenge, so wrong credentials would loop; stop once it was already sent.
    private static ProxyAuthenticator basicProxyAuthenticator(Logger logger, String username, String password) {
        var credentials = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.ISO_8859_1));
        return (route, request, response) -> {
            if (request.headers().values("Proxy-Authorization").isEmpty()) {
                return Optional.of(request.toBuilder().putHeader("Proxy-Authorization", credentials).build());
            }
            logger.warn("The proxy rejected the credentials (HTTP 407); check proxy.username and proxy.password.");
            return Optional.empty();
        };
    }

    protected void sendMetrics(RunContext runContext, Message message) {
        sendMetrics(
            runContext,
            message.usage().inputTokens(),
            message.usage().outputTokens(),
            message.usage().cacheCreationInputTokens().orElse(null),
            message.usage().cacheReadInputTokens().orElse(null)
        );
    }

    protected void sendMetrics(
        RunContext runContext,
        long inputTokens,
        long outputTokens,
        Long cacheCreationInputTokens,
        Long cacheReadInputTokens) {
        runContext.metric(Counter.of("usage.input.tokens", inputTokens));
        runContext.metric(Counter.of("usage.output.tokens", outputTokens));
        if (cacheCreationInputTokens != null) {
            runContext.metric(Counter.of("usage.cache.creation.tokens", cacheCreationInputTokens));
        }
        if (cacheReadInputTokens != null) {
            runContext.metric(Counter.of("usage.cache.read.tokens", cacheReadInputTokens));
        }
    }

    protected void sendMetrics(RunContext runContext, long inputTokens) {
        runContext.metric(Counter.of("estimate.input.tokens", inputTokens));
    }

    protected static String outputText(Message message) {
        var outputText = new StringBuilder();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(text -> outputText.append(text.text()));
        }
        return outputText.toString();
    }

    protected static List<ChatCompletion.ToolUse> toolUses(RunContext runContext, Message message) {
        List<ChatCompletion.ToolUse> toolUses = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            block.toolUse().ifPresent(
                toolUse -> toolUses.add(
                    ChatCompletion.ToolUse.builder()
                        .id(toolUse.id())
                        .name(toolUse.name())
                        .input(readToolInput(runContext, toolUse._input()))
                        .build()
                )
            );
        }
        return toolUses.isEmpty() ? null : List.copyOf(toolUses);
    }

    protected static String stopReason(Message message) {
        return message.stopReason().map(StopReason::asString).orElse(null);
    }

    protected static Tool toSdkTool(ChatCompletion.Tool tool) {
        var inputSchemaBuilder = Tool.InputSchema.builder();

        // Build input schema from the provided map
        if (tool.inputSchema() != null && tool.inputSchema().containsKey("properties")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            var propertiesBuilder = Tool.InputSchema.Properties.builder();

            // Convert properties to JsonValue
            properties.forEach((key, value) ->
            {
                JsonValue jsonValue = JsonValue.from(value);
                propertiesBuilder.putAdditionalProperty(key, jsonValue);
            });

            inputSchemaBuilder.properties(propertiesBuilder.build());
        }

        if (tool.inputSchema() != null) {
            // Add required fields if present
            if (tool.inputSchema().containsKey("required")) {
                @SuppressWarnings("unchecked")
                List<String> requiredFields = (List<String>) tool.inputSchema().get("required");
                inputSchemaBuilder.required(requiredFields);
            }

            tool.inputSchema().forEach((key, value) ->
            {
                if (!TYPED_SCHEMA_KEYS.contains(key)) {
                    inputSchemaBuilder.putAdditionalProperty(key, JsonValue.from(value));
                }
            });
        }

        var toolBuilder = Tool.builder()
            .name(tool.name())
            .inputSchema(inputSchemaBuilder.build());

        if (tool.description() != null && !tool.description().isEmpty()) {
            toolBuilder.description(tool.description());
        }
        if (tool.cacheControl() != null) {
            toolBuilder.cacheControl(ChatCompletion.cacheControl(tool.cacheControl()));
        }

        return toolBuilder.build();
    }

    private static Map<String, Object> readToolInput(RunContext runContext, JsonValue input) {
        try {
            var json = JacksonMapper.ofJson().writeValueAsString(input);
            return JacksonMapper.ofJson().readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            runContext.logger().warn("Unable to convert Anthropic tool input to a JSON object, so the tool input is null ({}).", e.getClass().getSimpleName());
            return null;
        }
    }
}
