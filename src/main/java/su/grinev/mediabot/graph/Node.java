package su.grinev.mediabot.graph;

import su.grinev.mediabot.media.AudioFormat;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;

import su.grinev.mediabot.media.Trim;

import java.util.List;

/**
 * One operation in a job, and where its input comes from.
 *
 * <p>A closed set, like {@code Request}: the pipeline text and the model can only ever name one of
 * these, so an operation that does not exist cannot be reached by spelling.
 */
public sealed interface Node {

    String id();

    List<String> inputs();

    String describe();

    record Fetch(String id, String url, Integer maxHeight) implements Node {

        @Override
        public List<String> inputs() {
            return List.of();
        }

        @Override
        public String describe() {
            return maxHeight == null ? "download" : "download up to " + maxHeight + "p";
        }
    }

    record Cut(String id, String input, Trim window) implements Node {

        @Override
        public List<String> inputs() {
            return List.of(input);
        }

        @Override
        public String describe() {
            return "cut " + window.describe();
        }
    }

    record Encode(String id, String input, Container container, Integer height, Quality quality)
            implements Node {

        public Encode {
            container = container == null ? Container.DEFAULT : container;
            quality = quality == null ? Quality.DEFAULT : quality;
            // A height written next to a profile wins; without one the profile brings its own.
            height = height == null ? quality.height() : height;
        }

        @Override
        public List<String> inputs() {
            return List.of(input);
        }

        @Override
        public String describe() {
            return "re-encode to %s%s %s".formatted(height == null ? "" : height + "p ",
                    container.extension(), quality.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    record Normalize(String id, String input) implements Node {

        @Override
        public List<String> inputs() {
            return List.of(input);
        }

        @Override
        public String describe() {
            return "normalize the loudness";
        }
    }

    record Audio(String id, String input, AudioFormat format) implements Node {

        public Audio {
            format = format == null ? AudioFormat.DEFAULT : format;
        }

        @Override
        public List<String> inputs() {
            return List.of(input);
        }

        @Override
        public String describe() {
            return "take the audio as " + format.extension();
        }
    }

    record Concat(String id, List<String> inputs) implements Node {

        @Override
        public String describe() {
            return "join " + inputs.size() + " pieces";
        }
    }

    record Publish(String id, String input) implements Node {

        @Override
        public List<String> inputs() {
            return List.of(input);
        }

        @Override
        public String describe() {
            return "publish";
        }
    }
}
