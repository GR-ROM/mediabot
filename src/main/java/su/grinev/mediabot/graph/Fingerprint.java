package su.grinev.mediabot.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A canonical description of the work, written for comparison and nothing else.
 *
 * <p>Deliberately not the storage format. A fingerprint taken from whatever the serialiser happened
 * to emit depends on field order, on how a null is spelled and on whether an empty value is
 * omitted — so it breaks when somebody reorders a {@code put(...)}, and it never matched between
 * two implementations of the same format. This is written out here, once, by hand:
 *
 * <pre>
 * fetch url=&lt;url&gt; h=&lt;height|-&gt;
 * cut in=&lt;n&gt; from=&lt;n&gt; to=&lt;n|-&gt;
 * encode in=&lt;n&gt; c=&lt;container&gt; q=&lt;QUALITY&gt; h=&lt;height|-&gt;
 * </pre>
 *
 * <p>One line per node in topological order, joined by newlines. An input is named by its position
 * in that order and never by its id: ids are minted by whichever builder assembled the graph, and
 * the same work put together two ways — by the pipeline text, by the model, by a future builder
 * that numbers its steps differently — has to fingerprint the same or the dedup is pointless.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(Graph graph) {
        List<Node> ordered = graph.inOrder();
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            positions.put(ordered.get(i).id(), i);
        }
        return ordered.stream()
                .map(node -> line(node, positions))
                .collect(Collectors.joining("\n"));
    }

    private static String line(Node node, Map<String, Integer> at) {
        return switch (node) {
            case Node.Fetch fetch ->
                    "fetch url=%s h=%s".formatted(fetch.url(), optional(fetch.maxHeight()));
            case Node.Cut cut ->
                    "cut in=%d from=%d to=%s".formatted(at.get(cut.input()),
                            cut.window().startSeconds(), optional(cut.window().endSeconds()));
            case Node.Encode encode ->
                    "encode in=%d c=%s q=%s h=%s".formatted(at.get(encode.input()),
                            encode.container().extension(), encode.quality().name(),
                            optional(encode.height()));
            case Node.Normalize normalize -> "normalize in=" + at.get(normalize.input());
            case Node.Audio audio ->
                    "audio in=%d f=%s".formatted(at.get(audio.input()),
                            audio.format().extension());
            case Node.Concat concat -> "join in=" + concat.inputs().stream()
                    .map(input -> String.valueOf(at.get(input)))
                    .collect(Collectors.joining(","));
            case Node.Publish publish -> "publish in=" + at.get(publish.input());
        };
    }

    private static String optional(Integer value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
