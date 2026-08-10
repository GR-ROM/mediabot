package su.grinev.mediabot.graph;

import su.grinev.mediabot.media.AudioFormat;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import su.grinev.mediabot.media.Trim;

import java.util.ArrayList;
import java.util.List;

/**
 * A graph as the text that goes in a column, and back.
 *
 * <p>Written by hand rather than by annotating {@link Node}: the discriminator is the whole of the
 * format, and a model with Jackson's polymorphism on it is a model whose stored shape changes when
 * somebody renames a record. This way the names in the column are chosen here, once, and a node
 * type the reader does not know stops the read instead of arriving as null.
 */
public final class GraphJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GraphJson() {
    }

    public static String write(Graph graph) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode nodes = root.putArray("nodes");
        for (Node node : graph.nodes()) {
            nodes.add(write(node));
        }
        return root.toString();
    }

    private static ObjectNode write(Node node) {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("id", node.id());
        switch (node) {
            case Node.Fetch fetch -> {
                json.put("op", "fetch");
                json.put("url", fetch.url());
                putHeight(json, fetch.maxHeight());
            }
            case Node.Cut cut -> {
                json.put("op", "cut");
                json.put("in", cut.input());
                json.put("from", cut.window().startSeconds());
                if (cut.window().endSeconds() == null) {
                    json.putNull("to");
                } else {
                    json.put("to", cut.window().endSeconds());
                }
            }
            case Node.Encode encode -> {
                json.put("op", "encode");
                json.put("in", encode.input());
                json.put("container", encode.container().extension());
                json.put("quality", encode.quality().name());
                putHeight(json, encode.height());
            }
            case Node.Normalize normalize -> {
                json.put("op", "normalize");
                json.put("in", normalize.input());
            }
            case Node.Audio audio -> {
                json.put("op", "audio");
                json.put("in", audio.input());
                json.put("format", audio.format().extension());
            }
            case Node.Concat concat -> {
                // "ins", not "in": this is the one step that takes several, and a key that is a
                // string everywhere else and an array here is a key a statically typed reader has
                // to special-case. The Go port would not spell it that way, and both write into
                // the same column.
                json.put("op", "join");
                ArrayNode inputs = json.putArray("ins");
                concat.inputs().forEach(inputs::add);
            }
            case Node.Publish publish -> {
                json.put("op", "publish");
                json.put("in", publish.input());
            }
        }
        return json;
    }

    public static Graph read(String text) {
        if (text == null || text.isBlank()) {
            throw new Pipeline.Invalid("there is no pipeline written down for this job");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (Exception e) {
            throw new Pipeline.Invalid("the stored pipeline is not readable: " + e.getMessage());
        }
        List<Node> nodes = new ArrayList<>();
        for (JsonNode json : root.path("nodes")) {
            nodes.add(readNode(json));
        }
        return new Graph(nodes);
    }

    private static Node readNode(JsonNode json) {
        String id = json.path("id").asText();
        String op = json.path("op").asText();
        return switch (op) {
            case "fetch" -> new Node.Fetch(id, json.path("url").asText(), height(json));
            case "cut" -> new Node.Cut(id, json.path("in").asText(), new Trim(
                    json.path("from").asInt(),
                    json.path("to").isInt() ? json.path("to").asInt() : null));
            case "encode" -> new Node.Encode(id, json.path("in").asText(),
                    Container.named(json.path("container").asText()).orElse(Container.DEFAULT),
                    height(json),
                    Quality.named(json.path("quality").asText()).orElse(Quality.DEFAULT));
            case "normalize" -> new Node.Normalize(id, json.path("in").asText());
            case "audio" -> new Node.Audio(id, json.path("in").asText(),
                    AudioFormat.named(json.path("format").asText()).orElse(AudioFormat.DEFAULT));
            case "join" -> {
                // Rows written before the key was settled say "in"; they are still somebody's job.
                JsonNode written = json.has("ins") ? json.path("ins") : json.path("in");
                List<String> inputs = new ArrayList<>();
                written.forEach(input -> inputs.add(input.asText()));
                yield new Node.Concat(id, inputs);
            }
            case "publish" -> new Node.Publish(id, json.path("in").asText());
            default -> throw new Pipeline.Invalid("the stored pipeline has a step this version does "
                    + "not know: " + op);
        };
    }

    private static void putHeight(ObjectNode json, Integer height) {
        if (height == null) {
            json.putNull("height");
        } else {
            json.put("height", height);
        }
    }

    private static Integer height(JsonNode json) {
        return json.path("height").isInt() ? json.path("height").asInt() : null;
    }
}
