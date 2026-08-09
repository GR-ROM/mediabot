package su.grinev.mediabot.llm;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thin client for an OpenAI-compatible /chat/completions endpoint — Ollama, LM Studio and vLLM
 * all speak this, so the agent is not tied to any one of them.
 */
@Slf4j
public class LlmClient {

    private final ModelHttp retrying;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String authHeader;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final Duration timeout;

    public LlmClient(ObjectMapper mapper, String baseUrl, String apiKey, String authHeader,
                     String model, int maxTokens, double temperature, int timeoutSeconds) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authHeader = Auth.resolve(apiKey, authHeader);
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.retrying = new ModelHttp(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1)
                .build(), "LLM at " + this.baseUrl);
    }

    /**
     * One completion round.
     *
     * @param messages full conversation so far, in OpenAI wire format
     * @param tools tool schemas, or empty for a plain completion
     */
    public Completion complete(List<ObjectNode> messages, List<ObjectNode> tools)
            throws IOException, InterruptedException {

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", false);

        ArrayNode msgs = body.putArray("messages");
        messages.forEach(msgs::add);

        if (!tools.isEmpty()) {
            ArrayNode toolArray = body.putArray("tools");
            tools.forEach(toolArray::add);
            body.put("tool_choice", "auto");
        }

        String payload = mapper.writeValueAsString(body);
        JsonNode response = post("/chat/completions", payload);
        return parse(response);
    }

    private JsonNode post(String path, String payload) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return mapper.readTree(retrying.send(builder.build()));
    }

    private Completion parse(JsonNode root) throws IOException {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("no choices in LLM response: " + root);
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").isNull() ? "" : message.path("content").asText("");

        List<ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText("call_" + calls.size());
                JsonNode fn = call.path("function");
                String name = fn.path("name").asText();
                calls.add(toolCall(id, name, fn.path("arguments")));
            }
        }
        return new Completion(answerText(message, content, calls), calls, root.path("usage"),
                choice.path("finish_reason").asText(""));
    }

    /**
     * The reply, from wherever this server put it.
     *
     * <p>A thinking model writes its reasoning into a field of its own and leaves {@code content}
     * empty — the failure {@code VisionService} already salvages, and the loop had no answer for.
     * There it produced "(the model returned an empty answer)", which names neither the cause nor
     * the fix. Only when the model asked for no tools: an empty {@code content} alongside tool
     * calls is ordinary, and the chain of thought is not the assistant turn then.
     */
    private static String answerText(JsonNode message, String content, List<ToolCall> calls) {
        String text = stripReasoning(content);
        if (!text.isBlank() || !calls.isEmpty()) {
            return text;
        }
        for (String field : new String[]{"reasoning", "reasoning_content"}) {
            String salvaged = stripReasoning(message.path(field).asText(""));
            if (!salvaged.isBlank()) {
                log.debug("answer salvaged from the '{}' field", field);
                return salvaged;
            }
        }
        return text;
    }

    /**
     * One tool call, usable or not. A model that ran out of tokens mid-argument sends JSON that
     * does not parse — and that is the model's mistake to be told about, not a reason to throw
     * out the whole request, which is what a parse error escaping here used to do.
     */
    private ToolCall toolCall(String id, String name, JsonNode args) {
        // Some servers send arguments as a JSON string, others as an object.
        if (args.isObject()) {
            return new ToolCall(id, name, (ObjectNode) args);
        }
        String raw = args.isMissingNode() || args.isNull() ? "" : args.asText("");
        if (raw.isBlank()) {
            return new ToolCall(id, name, mapper.createObjectNode());
        }
        try {
            JsonNode parsed = mapper.readTree(raw);
            if (!parsed.isObject()) {
                return malformed(id, name, "arguments were " + describe(parsed)
                        + ", not an object of named arguments");
            }
            return new ToolCall(id, name, (ObjectNode) parsed);
        } catch (JacksonException e) {
            return malformed(id, name, "the arguments are not valid JSON — " + e.getOriginalMessage());
        }
    }

    private ToolCall malformed(String id, String name, String problem) {
        log.debug("unusable arguments for {}: {}", name, problem);
        return new ToolCall(id, name, mapper.createObjectNode(), problem);
    }

    private static String describe(JsonNode node) {
        return node.isArray() ? "a JSON array"
                : node.isNull() ? "null"
                : "a bare " + node.getNodeType().toString().toLowerCase(Locale.ROOT);
    }

    /** Local reasoning models leak blocks into content. */
    private static String stripReasoning(String content) {
        return content.replaceAll("(?s)<think>.*?</think>", "").strip();
    }

    /**
     * @param finishReason the server's own word for why it stopped, or "" when it did not say.
     *     {@code length} means the answer was cut off at max_tokens — a partial answer presented
     *     as a whole one, which is the failure that looks like a valid result
     */
    public record Completion(String content, List<ToolCall> toolCalls, JsonNode usage,
                             String finishReason) {

        public Completion(String content, List<ToolCall> toolCalls, JsonNode usage) {
            this(content, toolCalls, usage, "");
        }

        public boolean wantsTools() {
            return !toolCalls.isEmpty();
        }

        /** True when the server ran out of answer budget rather than finishing. */
        public boolean truncated() {
            return "length".equals(finishReason);
        }
    }

    /**
     * @param problem why this call cannot be run, or null when it is usable. Carried rather than
     *     thrown, so the model is told what it got wrong and can call the tool again
     */
    public record ToolCall(String id, String name, ObjectNode arguments, String problem) {

        public ToolCall(String id, String name, ObjectNode arguments) {
            this(id, name, arguments, null);
        }

        public boolean isUsable() {
            return problem == null;
        }
    }
}
