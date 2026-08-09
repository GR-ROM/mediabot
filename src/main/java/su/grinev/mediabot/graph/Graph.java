package su.grinev.mediabot.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What to do with one video, as nodes and the edges between them.
 *
 * <p>A graph rather than a list of steps because the work branches: one source, several cuts, its
 * own encode on each, a link per result. A list cannot say what is whose input, and the answer to
 * that question is the whole of the difference.
 *
 * <p>Checked here, once, on construction — ids unique, every input resolving to a node, no cycle,
 * something to publish at the end. Everything downstream may then assume it is walking a graph that
 * makes sense.
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
        for (Node node : nodes) {
            for (String input : node.inputs()) {
                if (!ids.contains(input)) {
                    throw new IllegalArgumentException(
                            node.id() + " takes its input from " + input + ", which is not here");
                }
            }
        }
        if (nodes.stream().noneMatch(node -> node instanceof Node.Publish)) {
            throw new IllegalArgumentException("a job with nothing to publish would produce nothing");
        }
        order(nodes);
    }

    public List<Node> inOrder() {
        return order(nodes);
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

    private static List<Node> order(List<Node> nodes) {
        Map<String, Node> byId = new LinkedHashMap<>();
        nodes.forEach(node -> byId.put(node.id(), node));

        List<Node> sorted = new ArrayList<>(nodes.size());
        Set<String> done = new LinkedHashSet<>();
        Set<String> onPath = new LinkedHashSet<>();
        for (Node node : nodes) {
            visit(node, byId, done, onPath, sorted);
        }
        return List.copyOf(sorted);
    }

    private static void visit(Node node, Map<String, Node> byId, Set<String> done,
                              Set<String> onPath, List<Node> sorted) {
        if (done.contains(node.id())) {
            return;
        }
        if (!onPath.add(node.id())) {
            throw new IllegalArgumentException("the steps loop back on themselves at " + node.id());
        }
        for (String input : node.inputs()) {
            visit(byId.get(input), byId, done, onPath, sorted);
        }
        onPath.remove(node.id());
        done.add(node.id());
        sorted.add(node);
    }
}
