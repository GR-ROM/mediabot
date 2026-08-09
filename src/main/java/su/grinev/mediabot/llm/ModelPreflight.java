package su.grinev.mediabot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Asks the model server what it has before the first chat message does.
 *
 * <p>Worth doing at startup rather than on the first request, because both failures it catches are
 * silent at request time: a model that cannot call tools answers every request in prose with nothing
 * queued, which reads as an unhelpful model rather than as a misconfiguration.
 */
@Component
@Slf4j
public class ModelPreflight {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final String authHeader;

    public ModelPreflight(ObjectMapper mapper, AgentProperties props) {
        this.mapper = mapper;
        this.props = props;
        this.authHeader = Auth.resolve(props.llm().apiKey(), props.llm().authHeader());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** @return what is wrong, and whether the bot can drive its loop at all */
    public Result check() {
        if (!props.llm().preflight()) {
            return new Result(true, List.of(), List.of());
        }
        String base = trimSlash(props.llm().baseUrl());

        List<String> available;
        try {
            available = listModels(base);
        } catch (Exception e) {
            return new Result(false, List.of(
                    "Cannot reach the model server at " + base + " — " + describe(e),
                    "Start it, or point the bot at another one with --mediabot.llm.base-url=...",
                    "To start without one anyway, add --mediabot.llm.preflight=false."),
                    List.of());
        }

        List<String> warnings = new ArrayList<>();
        // A warning, not a refusal: servers name models in their own way, so a name we fail to find
        // may still be perfectly serveable.
        if (!available.isEmpty() && !hasModel(available, props.llm().model())) {
            warnings.add("The server does not list '" + props.llm().model()
                    + "', which parses the messages the router cannot read. It lists: "
                    + String.join(", ", available));
        }

        // Ollama answers this; anything else returns nothing and contributes no warnings. Tool
        // calling is not among the things asked about any more — the parser is handed a sentence
        // and answers with JSON, which every model of this size can do.
        Set<String> capabilities = capabilities(base, props.llm().model());
        if (capabilities.contains("thinking")) {
            warnings.add(("The model '%s' can think, and it is only ever asked for one JSON "
                    + "object. Its reasoning arrives in a field of its own and comes out of the "
                    + "%d-token budget, so the object may be cut off before it is finished. Prefer "
                    + "a model without the capability.")
                    .formatted(props.llm().model(), props.llm().maxTokens()));
        }
        return new Result(true, List.of(), warnings);
    }

    /** The OpenAI-compatible listing every one of these servers implements. */
    private List<String> listModels(String base) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(base + "/models"))
                .timeout(TIMEOUT)
                .GET();
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        HttpResponse<String> res = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            // Reachable but unwilling to list; completions may still work.
            log.debug("model listing returned {}", res.statusCode());
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode model : mapper.readTree(res.body()).path("data")) {
            String id = model.path("id").asText("");
            if (!id.isBlank()) {
                names.add(id);
            }
        }
        return names;
    }

    /**
     * What a model can do, as the server describes it.
     *
     * <p>Ollama reports this outside the OpenAI shape, at /api/show on its own root. Other servers do
     * not have it, so an empty set means "this server would not say" and never "the model cannot" —
     * a vLLM host would otherwise collect a warning for each capability it simply does not advertise.
     */
    private Set<String> capabilities(String base, String model) {
        if (model == null || model.isBlank()) {
            return Set.of();
        }
        String root = base.endsWith("/v1") ? base.substring(0, base.length() - 3) : base;
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(root + "/api/show"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.createObjectNode().put("model", model).toString()));
            if (authHeader != null) {
                builder.header("Authorization", authHeader);
            }
            HttpResponse<String> res = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return Set.of();
            }
            Set<String> found = new LinkedHashSet<>();
            for (JsonNode capability : mapper.readTree(res.body()).path("capabilities")) {
                String name = capability.asText("");
                if (!name.isBlank()) {
                    found.add(name);
                }
            }
            return found;
        } catch (Exception e) {
            log.debug("capability probe not available for {}: {}", model, e.toString());
            return Set.of();
        }
    }

    /** Servers tag models in their own way, so accept a name that is recognisably the same one. */
    private static boolean hasModel(List<String> available, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return true;
        }
        String needle = wanted.toLowerCase(Locale.ROOT);
        return available.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.equals(needle)
                        || name.startsWith(needle)
                        || needle.startsWith(name));
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** ConnectException carries no message, and "— null" tells the user nothing. */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * @param usable false when the bot cannot work at all
     * @param problems why it cannot
     * @param warnings things that will bite later, but not now
     */
    public record Result(boolean usable, List<String> problems, List<String> warnings) {}
}
