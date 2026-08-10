package su.grinev.mediabot.graph;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What to do with one video, as nodes and the edges between them.
 *
 * <p>A graph rather than a list of steps because the work branches: one source, several cuts, its
 * own encode on each, a link per result. A list cannot say what is whose input, and the answer to
 * that question is the whole of the difference.
 *
 * <p>Checked here, once, on construction — ids unique, every input resolving to a node that comes
 * before it, something to publish at the end. Everything downstream may then assume it is walking a
 * graph that makes sense.
 *
 * <p>Checked and never reordered. The order of the steps is the person's, not ours: {@code /audio
 * /cut} takes the sound and cuts that, {@code /cut /audio} cuts the video and takes the sound of
 * each piece, and those are different jobs. A graph that arrives out of order is a graph somebody
 * wrote down wrong, and quietly rearranging it would answer a question nobody asked.
 *
 * <p>Which is also what makes the cycle check free rather than lost. If every node must stand after
 * its own inputs, a loop cannot be expressed at all — "a before b" and "b before a" is not a list —
 * so one linear pass answers what a depth-first walk with two sets used to.
 */
public record Graph(List<Node> nodes) {

    public Graph {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("a job has to do something");
        }
        nodes = List.copyOf(nodes);

        Set<String> ids = new HashSet<>();
        for (Node node : nodes) {
            if (node.id() == null || node.id().isBlank()) {
                throw new IllegalArgumentException("every node needs an id");
            }
            if (!ids.add(node.id())) {
                throw new IllegalArgumentException("two nodes share the id " + node.id());
            }
        }
        // One pass, in the order the steps were written. A node may only take its input from one
        // already behind it, which is three checks at once: the input exists, it is not this node's
        // own descendant, and the list runs the way it reads.
        Set<String> behind = new HashSet<>();
        for (Node node : nodes) {
            for (String input : node.inputs()) {
                if (behind.contains(input)) {
                    continue;
                }
                throw new IllegalArgumentException(ids.contains(input)
                        ? node.id() + " takes its input from " + input + ", which does not come "
                                + "before it — the steps either loop or are written out of order"
                        : node.id() + " takes its input from " + input + ", which is not here");
            }
            behind.add(node.id());
        }
        if (nodes.stream().noneMatch(node -> node instanceof Node.Publish)) {
            throw new IllegalArgumentException("a job with nothing to publish would produce nothing");
        }
    }

    /**
     * The steps in the order they run, which is the order they were written in.
     *
     * <p>Kept as its own name because that is what the callers mean by it, and because it is the
     * one guarantee this class makes about the list beyond its contents.
     */
    public List<Node> inOrder() {
        return nodes;
    }

    public List<Node.Publish> outputs() {
        return nodes.stream()
                .filter(Node.Publish.class::isInstance)
                .map(Node.Publish.class::cast)
                .toList();
    }

    public Optional<Node.Fetch> source() {
        return nodes.stream()
                .filter(Node.Fetch.class::isInstance)
                .map(Node.Fetch.class::cast)
                .findFirst();
    }

    public Node byId(String id) {
        return nodes.stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no node " + id));
    }

    /** What was asked for, in the words a person would use. */
    public String describe() {
        List<String> steps = inOrder().stream()
                .filter(node -> !(node instanceof Node.Publish))
                .map(Node::describe)
                .distinct()
                .toList();
        int results = outputs().size();
        return String.join(", then ", steps) + (results > 1 ? " — " + results + " files" : "");
    }
}
