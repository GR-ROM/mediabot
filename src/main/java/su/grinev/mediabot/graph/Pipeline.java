package su.grinev.mediabot.graph;

import su.grinev.mediabot.media.AudioFormat;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;

import su.grinev.mediabot.media.Trim;

import java.util.ArrayList;
import java.util.List;

/**
 * A graph, built one step at a time.
 *
 * <p>No text reaches this class. {@link PipelineText} reads the slash form into these calls and the
 * router builds the one-step requests with the same ones, so there is a single place where a graph
 * is assembled and a single place where an impossible one is refused.
 *
 * <p>Every step applies to whatever branches are open, which is what lets a flat sequence of calls
 * describe work that forks: {@link #cut} with two windows leaves two branches, and one
 * {@link #encode} after it encodes both. {@link #join} is the exception, and has to be asked for,
 * because "do it to each" is precisely wrong for joining.
 */
public final class Pipeline {

    public static class Invalid extends IllegalArgumentException {
        public Invalid(String message) {
            super(message);
        }
    }

    private final List<Node> nodes = new ArrayList<>();
    private List<String> branches = new ArrayList<>();
    private int counter;

    private Pipeline() {
    }

    public static Pipeline download(String url, Integer maxHeight) {
        if (url == null || url.isBlank()) {
            throw new Invalid("there is no link to download");
        }
        Pipeline pipeline = new Pipeline();
        pipeline.branches.add(pipeline.add(new Node.Fetch("src", url.strip(), maxHeight)));
        return pipeline;
    }

    public Pipeline cut(List<Trim> windows) {
        if (windows == null || windows.isEmpty()) {
            throw new Invalid("a cut needs a range, like 1:00-2:00");
        }
        return spread(branch -> windows.stream()
                .map(window -> (Node) new Node.Cut(id("cut"), branch, window))
                .toList());
    }

    public Pipeline encode(Container container, Integer height, Quality quality) {
        return map(branch -> new Node.Encode(id("enc"), branch, container, height, quality));
    }

    public Pipeline normalize() {
        return map(branch -> new Node.Normalize(id("norm"), branch));
    }

    public Pipeline audio(AudioFormat format) {
        return map(branch -> new Node.Audio(id("audio"), branch, format));
    }

    public Pipeline join() {
        if (branches.size() < 2) {
            throw new Invalid("there is only one piece here, so there is nothing to join it to");
        }
        String joined = add(new Node.Concat(id("join"), List.copyOf(branches)));
        branches = new ArrayList<>(List.of(joined));
        return this;
    }

    public Pipeline publish() {
        return map(branch -> new Node.Publish(id("out"), branch));
    }

    public Graph build() {
        if (nodes.stream().noneMatch(node -> node instanceof Node.Publish)) {
            publish();
        }
        return new Graph(nodes);
    }

    private Pipeline map(java.util.function.Function<String, Node> step) {
        return spread(branch -> List.of(step.apply(branch)));
    }

    private Pipeline spread(java.util.function.Function<String, List<Node>> step) {
        if (branches.isEmpty()) {
            throw new Invalid("there is nothing to work on yet — start with a link");
        }
        List<String> next = new ArrayList<>();
        for (String branch : branches) {
            step.apply(branch).forEach(node -> next.add(add(node)));
        }
        branches = next;
        return this;
    }

    private String add(Node node) {
        nodes.add(node);
        return node.id();
    }

    private String id(String prefix) {
        return prefix + ++counter;
    }
}
