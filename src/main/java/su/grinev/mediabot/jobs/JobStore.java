package su.grinev.mediabot.jobs;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.db.Database;
import su.grinev.mediabot.graph.Graph;
import su.grinev.mediabot.graph.GraphJson;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where jobs live between being asked for and being delivered.
 *
 * <p>On disk rather than in a queue in memory, for one reason: a download is minutes long, and a
 * process that dies in the middle of one must not leave somebody waiting for a file that nothing is
 * producing any more. What survives a restart is not the work — that is gone — but the knowledge
 * that it was promised, which is what lets the bot say so.
 *
 * <p>The connection and the lock belong to {@link Database}, shared with everything else that
 * writes to this file. SQLite serialises writers whatever we do; the only choice is whether they
 * queue politely in this process or find out through SQLITE_BUSY.
 */
@Component
@Slf4j
public class JobStore {

    /** The states a job can still move out of on its own. */
    private static final String UNFINISHED = "('PENDING','DOWNLOADING','PROCESSING')";

    private final Database database;
    private final int retentionHours;
    private final Map<Long, Job> inFlight = new ConcurrentHashMap<>();

    /**
     * Annotated because {@link #on} makes this the second declared constructor, and Spring only
     * picks one on its own when there is exactly one to pick — private ones included in the count.
     * Without this it looks for a default constructor and fails at startup.
     */
    @Autowired
    public JobStore(Database database, AgentProperties props) {
        this(database, props.jobs().retentionHours());
    }

    /**
     * A store on a given database, for a test that wants one without a whole configuration.
     *
     * <p>A factory over a second constructor because two constructors leave Spring with no way to
     * tell which one it is meant to inject — it stops choosing and looks for a default one instead.
     */
    static JobStore on(Database database, int retentionHours) {
        JobStore store = new JobStore(database, retentionHours);
        store.createTable();
        return store;
    }

    private JobStore(Database database, int retentionHours) {
        this.database = database;
        this.retentionHours = retentionHours;
    }

    @PostConstruct
    void createTable() {
        database.call("cannot create the jobs table", connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("""
                        create table if not exists jobs (
                          id           integer primary key autoincrement,
                          chat_id      integer not null,
                          kind         text    not null,
                          url          text    not null,
                          max_height   integer,
                          audio_format text,
                          state        text    not null,
                          created_at   integer not null,
                          updated_at   integer not null,
                          title        text,
                          result_path  text,
                          size_bytes   integer not null default 0,
                          error        text
                        )""");
                s.execute("create index if not exists jobs_chat_state on jobs(chat_id, state)");
                s.execute("create index if not exists jobs_state on jobs(state)");

                // The graph, denormalised into one column rather than a table of nodes pointing at
                // each other: it is written once, read whole, and never queried into — the fields
                // anything does query on are the flat ones beside it. A node table would buy a
                // recursive read of a handful of rows and nothing else.
                if (!hasColumn(connection, "spec_json")) {
                    s.execute("alter table jobs add column spec_json blob");
                }
                // A graph with two cuts finishes with two files. result_path keeps the first, so
                // everything written before branching existed still reads a job the way it always
                // did, and only what wants all of them looks here.
                if (!hasColumn(connection, "results_json")) {
                    s.execute("alter table jobs add column results_json blob");
                }
                // The fingerprint a repeated request is recognised by. Rows written before it
                // existed have none and are simply never reused — which is the safe direction.
                if (!hasColumn(connection, "origin")) {
                    s.execute("alter table jobs add column origin text");
                }
                s.execute("create index if not exists jobs_origin on jobs(chat_id, origin)");

                // Rows written before the states were named after what they mean. Done here rather
                // than read leniently at every query, because a state nothing writes any more
                // should stop existing rather than be understood forever.
                s.executeUpdate("update jobs set state = 'PENDING' where state = 'QUEUED'");
                s.executeUpdate("update jobs set state = 'IN_PROGRESS' where state = 'RUNNING'");
                // Downloading and processing used to be one state. Rows carrying the older name
                // belonged to a process that died, so they map to the first half.
                s.executeUpdate("update jobs set state = 'DOWNLOADING' where state = 'IN_PROGRESS'");
            }
            return null;
        });
    }

    /**
     * Records a job as queued.
     *
     * @return the job, with the id it was given
     */
    public Job create(JobSpec spec) {
        return database.call("cannot record the job", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    insert into jobs (chat_id, kind, url, max_height, audio_format, state,
                                      created_at, updated_at, title, spec_json, origin)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, jsonb(?), ?)""",
                    Statement.RETURN_GENERATED_KEYS)) {
                long now = Instant.now().toEpochMilli();
                ps.setLong(1, spec.chatId());
                ps.setString(2, spec.scenario().name());
                ps.setString(3, spec.url());
                if (spec.maxHeight() == null) {
                    ps.setNull(4, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(4, spec.maxHeight());
                }
                ps.setString(5, spec.audioFormat());
                ps.setString(6, JobState.PENDING.name());
                ps.setLong(7, now);
                ps.setLong(8, now);
                ps.setString(9, spec.title());
                ps.setString(10, GraphJson.write(spec.graph()));
                ps.setString(11, spec.origin());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return new Job(keys.getLong(1), spec.chatId(), spec.scenario(), spec.url(),
                            spec.maxHeight(), spec.audioFormat(), spec.graph(), JobState.PENDING,
                            Instant.ofEpochMilli(now), Instant.ofEpochMilli(now), spec.title(),
                            null, 0, null);
                }
            }
        });
    }

    public Optional<Job> find(long id) {
        return database.call("cannot read job " + id, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "select *, json(spec_json) as spec_text, json(results_json) as results_text from jobs where id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<Job>empty();
                }
            }
        });
    }

    /** Everything this chat has that has not finished, oldest first. */
    public List<Job> pendingIn(long chatId) {
        return query("select *, json(spec_json) as spec_text, json(results_json) as results_text from jobs where chat_id = ? and state in " + UNFINISHED
                + " order by id", chatId);
    }

    /** The chat's recent jobs whatever their state, newest first. */
    public List<Job> recentIn(long chatId, int limit) {
        return query("select *, json(spec_json) as spec_text, json(results_json) as results_text from jobs where chat_id = ? order by id desc limit ?", chatId, limit);
    }

    /**
     * An unfinished job for the same chat, url and height.
     *
     * <p>Dedup rather than a second download: a person who sends the same link twice, or a caller
     * that asks again because the first answer did not sound final, means the same thing both
     * times — and the second download would deliver the same file at twice the cost.
     */
    /**
     * What makes a repeated request cost nothing.
     *
     * <p>Asked before anything is queued: a job in this chat with the same fingerprint that is
     * either still running or finished with its files still around is the answer to the new
     * request, and doing the work again would spend the bandwidth and the CPU twice to produce the
     * same bytes.
     *
     * <p>Scoped to the chat because a link is minted for one, and handing another chat's link over
     * would be handing over access to it. Failed and expired jobs are not reused: there is nothing
     * to hand back, and refusing to retry would be worse than retrying.
     */
    public Optional<Job> findReusable(JobSpec spec) {
        String origin = spec.origin();
        if (origin == null || origin.isBlank()) {
            return Optional.empty();
        }
        List<Job> candidates = query("select *, json(spec_json) as spec_text, "
                + "json(results_json) as results_text from jobs "
                + "where chat_id = ? and origin = ? "
                + "and state in ('PENDING','DOWNLOADING','PROCESSING','DONE') "
                + "order by id desc", spec.chatId(), origin);

        for (Job job : candidates) {
            // Finished with nothing to show for it is not something to hand back.
            if (job.state() == JobState.DONE && job.results().isEmpty()) {
                continue;
            }
            return Optional.of(job);
        }
        return Optional.empty();
    }

    public Optional<Job> findPendingLike(JobSpec spec) {
        for (Job job : pendingIn(spec.chatId())) {
            if (job.kind() == spec.scenario() && job.url().equals(spec.url())
                    && java.util.Objects.equals(job.maxHeight(), spec.maxHeight())) {
                return Optional.of(job);
            }
        }
        return Optional.empty();
    }

    /**
     * Hands the oldest waiting job to the caller, and to nobody else.
     *
     * <p>The claim is the state change: a row read as {@code PENDING} and written as
     * {@code DOWNLOADING} in one go, so two workers asking at the same moment cannot come away with
     * the same job. Both statements run inside one held lock, which is what makes the pair atomic —
     * the {@code and state = 'PENDING'} on the update is there for the day somebody points a second
     * process at this file.
     *
     * @return the job, already marked as running, or empty when nothing is waiting
     */
    public Optional<Job> claimNext() {
        return database.call("cannot claim a job", connection -> {
            List<Job> next = query("select *, json(spec_json) as spec_text, json(results_json) as results_text from jobs where state = 'PENDING' order by id limit 1");
            if (next.isEmpty()) {
                return Optional.<Job>empty();
            }
            long id = next.getFirst().id();
            try (PreparedStatement ps = connection.prepareStatement(
                    "update jobs set state = ?, updated_at = ? where id = ? and state = 'PENDING'")) {
                ps.setString(1, JobState.DOWNLOADING.name());
                ps.setLong(2, Instant.now().toEpochMilli());
                ps.setLong(3, id);
                if (ps.executeUpdate() == 0) {
                    return Optional.<Job>empty();
                }
            }
            Optional<Job> claimed = find(id);
            claimed.ifPresent(job -> inFlight.put(job.id(), job));
            return claimed;
        });
    }

    public Job markTitle(long id, String title) {
        return update(id, "title = ?, updated_at = ?", ps -> {
            ps.setString(1, title);
            ps.setLong(2, Instant.now().toEpochMilli());
        });
    }

    public Job markState(long id, JobState state) {
        return update(id, "state = ?, updated_at = ?", ps -> {
            ps.setString(1, state.name());
            ps.setLong(2, Instant.now().toEpochMilli());
        });
    }

    public Job markDone(long id, JobState state, Path result, long sizeBytes) {
        return markDone(id, state, result == null ? List.of() : List.of(result), sizeBytes);
    }

    public Job markDone(long id, JobState state, List<Path> results, long sizeBytes) {
        Path first = results.isEmpty() ? null : results.getFirst();
        return update(id,
                "state = ?, result_path = ?, results_json = jsonb(?), size_bytes = ?, updated_at = ?",
                ps -> {
                    ps.setString(1, state.name());
                    ps.setString(2, first == null ? null : first.toString());
                    ps.setString(3, pathsToJson(results));
                    ps.setLong(4, sizeBytes);
                    ps.setLong(5, Instant.now().toEpochMilli());
                });
    }

    private static String pathsToJson(List<Path> paths) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(paths.get(i).toString()
                    .replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return json.append(']').toString();
    }

    public Job markFailed(long id, JobState state, String error) {
        return update(id, "state = ?, error = ?, updated_at = ?", ps -> {
            ps.setString(1, state.name());
            ps.setString(2, error);
            ps.setLong(3, Instant.now().toEpochMilli());
        });
    }

    /**
     * Called once at startup: anything still running belonged to a process that is gone.
     *
     * <p>Marked, not resumed. An hour after a crash the video is usually no longer wanted, and the
     * bandwidth is real — so the person is asked instead of being surprised by a file they have
     * stopped expecting.
     *
     * @return the jobs that were interrupted, so somebody can be told
     */
    public List<Job> markInterrupted() {
        return database.call("cannot mark interrupted jobs", connection -> {
            List<Job> caught = query("select *, json(spec_json) as spec_text, json(results_json) as results_text from jobs where state in " + UNFINISHED
                    + " order by id");
            if (caught.isEmpty()) {
                return List.<Job>of();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "update jobs set state = ?, updated_at = ? where state in " + UNFINISHED)) {
                ps.setString(1, JobState.INTERRUPTED.name());
                ps.setLong(2, Instant.now().toEpochMilli());
                ps.executeUpdate();
            }
            log.warn("{} job(s) were interrupted by a restart", caught.size());
            return caught.stream()
                    .map(j -> new Job(j.id(), j.chatId(), j.kind(), j.url(), j.maxHeight(),
                            j.audioFormat(), JobState.INTERRUPTED, j.createdAt(), Instant.now(),
                            j.title(), null, 0,
                            "the bot restarted while this was downloading"))
                    .toList();
        });
    }

    /** Drops finished rows older than the retention window. */
    public int sweep() {
        return database.callQuietly("could not sweep old jobs", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "delete from jobs where state not in " + UNFINISHED + " and updated_at < ?")) {
                ps.setLong(1, Instant.now().minusSeconds(retentionHours * 3600L).toEpochMilli());
                int removed = ps.executeUpdate();
                if (removed > 0) {
                    log.debug("swept {} finished job(s)", removed);
                }
                return removed;
            }
        }, 0);
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Job update(long id, String setClause, Binder binder) {
        database.call("cannot update job " + id, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "update jobs set " + setClause + " where id = " + id)) {
                binder.bind(ps);
                ps.executeUpdate();
            }
            return null;
        });
        return find(id).orElseThrow(() -> new IllegalStateException("job " + id + " vanished"));
    }

    private List<Job> query(String sql, Object... args) {
        return database.call("cannot list jobs", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                List<Job> jobs = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        jobs.add(read(rs));
                    }
                }
                return jobs;
            }
        });
    }

    private static boolean hasColumn(java.sql.Connection connection, String name)
            throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("pragma table_info(jobs)")) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Rows written before there were graphs have no column to read, and rebuilding one from the flat
     * fields is exactly what a job of that age was: a fetch and a publish. So there is no data
     * migration to run, and a queue that survived a restart does not care which version wrote it.
     */
    private static Graph graphIn(ResultSet rs) {
        try {
            String text = rs.getString("spec_text");
            return text == null || text.isBlank() ? null : GraphJson.read(text);
        } catch (SQLException | RuntimeException e) {
            log.warn("could not read the stored pipeline, falling back to the flat fields: {}",
                    e.toString());
            return null;
        }
    }

    private static List<Path> resultsIn(ResultSet rs) {
        try {
            String text = rs.getString("results_text");
            if (text == null || text.isBlank()) {
                return List.of();
            }
            List<Path> paths = new ArrayList<>();
            for (var node : new com.fasterxml.jackson.databind.ObjectMapper().readTree(text)) {
                paths.add(Path.of(node.asText()));
            }
            return paths;
        } catch (SQLException | RuntimeException | java.io.IOException e) {
            log.debug("could not read the result list: {}", e.toString());
            return List.of();
        }
    }

    private Job read(ResultSet rs) throws SQLException {
        int height = rs.getInt("max_height");
        // Read immediately: wasNull() answers about the last column read, so any getter in between
        // makes it answer about that one instead.
        boolean noHeight = rs.wasNull() || height == 0;
        String result = rs.getString("result_path");
        Job row = new Job(
                rs.getLong("id"),
                rs.getLong("chat_id"),
                JobKind.valueOf(rs.getString("kind")),
                rs.getString("url"),
                noHeight ? null : height,
                rs.getString("audio_format"),
                graphIn(rs),
                JobState.valueOf(rs.getString("state")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")),
                rs.getString("title"),
                result == null ? null : Path.of(result),
                rs.getLong("size_bytes"),
                rs.getString("error"));

        row.results(resultsIn(rs));

        Job live = inFlight.get(row.id());
        if (live == null) {
            return row;
        }
        live.sync(row);
        if (row.state().isFinished()) {
            inFlight.remove(row.id());
        }
        return live;
    }
}
