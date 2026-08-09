package su.grinev.mediabot.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.graph.Graph;
import su.grinev.mediabot.graph.Node;
import su.grinev.mediabot.jobs.JobKind;
import su.grinev.mediabot.jobs.JobSpec;
import su.grinev.mediabot.route.Request;
import su.grinev.mediabot.route.RequestRouter;

import java.util.List;

/**
 * The model's one chance to add to what the grammar already read.
 *
 * <p>{@code RequestRouter} turns any message with a link into a download, which is right almost
 * always and leaves no room for the things only a graph can say — cutting, normalising, joining.
 * Rather than take the default away, this offers to grow it: the reading is already a download, and
 * the model may only turn it into a longer pipeline over the same link.
 *
 * <p>That direction is the whole safety argument. It cannot invent a download, because one was
 * happening anyway; it cannot cancel one, because a refusal, an unreachable host or an answer that
 * is no richer all fall back to the reading it was given. The worst outcome is a wasted call.
 */
@Component
@Slf4j
public class PipelineUpgrade {

    /** A plain download is a fetch and a publish, and nothing to grow from is nothing to ask about. */
    private static final int PLAIN = 2;

    private final RequestRouter router;
    private final IntentParser parser;

    public PipelineUpgrade(RequestRouter router, IntentParser parser) {
        this.router = router;
        this.parser = parser;
    }

    public Request applyTo(long chatId, String text, Request read) {
        if (!(read instanceof Request.Fetch fetch)) {
            return read;
        }
        JobSpec routed = fetch.spec();
        if (routed.scenario() != JobKind.DOWNLOAD
                || routed.graph().nodes().size() > PLAIN
                || !router.hasWordsItDidNotUse(text)) {
            return read;
        }
        return parser.parse(chatId, text)
                .filter(upgraded -> upgraded.graph().nodes().size() > PLAIN)
                .map(upgraded -> keepHeight(routed, upgraded))
                .map(upgraded -> {
                    log.info("read as a download, upgraded to {}", upgraded.graph().describe());
                    return (Request) new Request.Fetch(upgraded);
                })
                .orElse(read);
    }

    /**
     * A height the person typed outranks one the model left out. The other way round is not a
     * conflict worth resolving: a model that named a height was reading the same sentence.
     */
    private static JobSpec keepHeight(JobSpec routed, JobSpec upgraded) {
        if (routed.maxHeight() == null || upgraded.maxHeight() != null
                || upgraded.scenario() == JobKind.AUDIO) {
            return upgraded;
        }
        List<Node> rewritten = upgraded.graph().nodes().stream()
                .map(node -> node instanceof Node.Fetch source
                        ? (Node) new Node.Fetch(source.id(), source.url(), routed.maxHeight())
                        : node)
                .toList();
        return JobSpec.of(upgraded.chatId(), new Graph(rewritten));
    }
}
