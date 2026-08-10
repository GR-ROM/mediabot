package su.grinev.mediabot.db;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one connection to the one file, and the lock that owns it.
 *
 * <p>There were two before — one for jobs, one for links — each behind its own lock, both writing
 * to the same database. SQLite lets exactly one connection write at a time, so two independent
 * locks guard nothing: the loser of the race got SQLITE_BUSY, which surfaced as a finished download
 * that could not be handed over because a worker happened to be writing a row in the same
 * millisecond. One owner makes that impossible rather than unlikely.
 *
 * <p>Reentrant on purpose. {@code claimNext} reads a row and updates it inside one held lock, and
 * {@code update} finishes by re-reading the row it just wrote — nesting is the normal case here,
 * not an accident to be designed out.
 *
 * <p>No pool. A pool answers "connections are expensive to open and the server drops them", and
 * SQLite is a file in this process: no handshake, no idle timeout, nothing to keep alive. What a
 * pool would buy is concurrent readers, and every query here is a point lookup on an indexed table
 * of a few hundred rows. Writers it cannot help at all — SQLite serialises those whatever we do.
 */
@Component
@Slf4j
public class Database {

    /** How long a writer waits for the other one rather than failing outright. */
    private static final int BUSY_TIMEOUT_MS = 5_000;

    private final Path path;
    private final String url;
    private final ReentrantLock lock = new ReentrantLock();
    private Connection connection;

    /**
     * Annotated because {@link #at} makes this the second declared constructor, and Spring only
     * picks one on its own when there is exactly one to pick — private ones included in the count.
     */
    @Autowired
    public Database(AgentProperties props) {
        this(props.jobs().databasePath());
    }

    private Database(Path path) {
        this.path = path;
        this.url = "jdbc:sqlite:" + path;
    }

    private Database(String url) {
        this.path = null;
        this.url = url;
    }

    /** A database on a given file, already open, for a test that wants one without a context. */
    public static Database at(Path file) throws SQLException, IOException {
        Database database = new Database(file);
        database.open();
        return database;
    }

    /**
     * A database that lives in this process and goes when it does.
     *
     * <p>For tests, and it is the right shape for them rather than merely the faster one: SQLite's
     * in-memory database belongs to a single connection, and a single connection is exactly what
     * this class already is. Everything below the connection — the schema, the transactions, the
     * claim-once update — is the same code the deployed bot runs, which a mocked store would not
     * have been.
     */
    public static Database inMemory() throws SQLException, IOException {
        Database database = new Database("jdbc:sqlite::memory:");
        database.open();
        return database;
    }

    /** A unit of work that needs the connection, run with the lock held. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    @PostConstruct
    public void open() throws SQLException, IOException {
        if (path != null) {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }
        connection = DriverManager.getConnection(url);
        try (Statement s = connection.createStatement()) {
            // WAL so a reader asking "what is queued?" is not blocked by a worker writing progress.
            // A no-op on an in-memory database, which has no file to journal to.
            s.execute("pragma journal_mode=WAL");
            s.execute("pragma synchronous=NORMAL");
            // Belt to the lock's braces: another process holding the file — a sqlite3 shell, a
            // backup — is not something this one can serialise with.
            s.execute("pragma busy_timeout=" + BUSY_TIMEOUT_MS);
        }
        log.info("database at {}", path == null ? "memory" : path.toAbsolutePath());
    }

    @PreDestroy
    public void close() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    /**
     * Runs {@code work} with the connection, under the lock.
     *
     * @throws IllegalStateException wrapping whatever SQLite said, with {@code what} to say where
     */
    public <T> T call(String what, Work<T> work) {
        lock.lock();
        try {
            return work.run(connection);
        } catch (SQLException e) {
            throw new IllegalStateException(what, e);
        } finally {
            lock.unlock();
        }
    }

    /** The same, for work whose failure is not worth stopping the caller over. */
    public <T> T callQuietly(String what, Work<T> work, T fallback) {
        lock.lock();
        try {
            return work.run(connection);
        } catch (SQLException e) {
            log.warn("{}: {}", what, e.toString());
            return fallback;
        } finally {
            lock.unlock();
        }
    }
}
