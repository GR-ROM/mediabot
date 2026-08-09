package su.grinev.mediabot.access;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.db.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Who may use the bot, kept in the table rather than the file.
 *
 * <p>The owner is the exception, and deliberately: it stays in the configuration, so an empty table,
 * a botched delete or a database that will not open cannot lock the one person who could fix it out
 * of their own bot. Everything else is a row, which is what lets somebody be let in from the chat
 * instead of by a restart.
 *
 * <p>The list is read on every message and written rarely, so it is held in memory and the table is
 * the record rather than the lookup. A chat that was just allowed works on its next message with no
 * restart, which is the entire point of moving it here.
 */
@Component
@Slf4j
public class AccessList {

    public record Entry(long chatId, Instant addedAt, long addedBy, String note) {}

    private final Database database;
    private final List<Long> owners;
    private final List<Long> seed;
    private final int guestCeiling;
    private final int ownerCeiling;
    private volatile List<Long> allowed = List.of();

    public AccessList(Database database, AgentProperties props) {
        this.database = database;
        this.owners = List.copyOf(props.telegram().owners());
        this.seed = List.copyOf(props.telegram().allowedChats());
        this.guestCeiling = props.media().maxHeight();
        this.ownerCeiling = props.media().ownerMaxHeight();
    }

    @PostConstruct
    void createTable() {
        boolean fresh = database.call("cannot create the access table", connection -> {
            boolean existed;
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery(
                         "select name from sqlite_master where type='table' "
                                 + "and name='allowed_chats'")) {
                existed = rs.next();
            }
            try (Statement s = connection.createStatement()) {
                s.execute("""
                        create table if not exists allowed_chats (
                          chat_id  integer primary key,
                          added_at integer not null,
                          added_by integer not null,
                          note     text
                        )""");
            }
            return !existed;
        });
        if (fresh) {
            seedFromConfiguration();
        }
        reload();
        log.info("access: {} owner(s), {} allowed chat(s)", owners.size(), allowed.size());
    }

    /**
     * The configured list becomes rows once, when the table is first made.
     *
     * <p>On creation and not on emptiness, which is the difference between a list that has not been
     * moved yet and one somebody deliberately emptied. Seeding the second would silently undo every
     * {@code /deny} on the next restart, and the person taken out would be back in without anybody
     * having asked.
     */
    private void seedFromConfiguration() {
        if (seed.isEmpty()) {
            return;
        }
        seed.forEach(chatId -> allow(chatId, 0, "from the configuration"));
        log.info("access: moved {} chat(s) from mediabot.telegram.allowed-chats into the table",
                seed.size());
    }

    public boolean isOwner(long chatId) {
        return owners.contains(chatId);
    }

    /** The tallest this chat may ask for, which is the one thing guests and owners differ on. */
    public int ceilingFor(long chatId) {
        return isOwner(chatId) ? ownerCeiling : guestCeiling;
    }

    /** Owners always, and anybody on the list. An empty list no longer means everybody. */
    public boolean permits(long chatId) {
        return isOwner(chatId) || allowed.contains(chatId);
    }

    /** @return false when that chat could already use the bot */
    public boolean allow(long chatId, long addedBy, String note) {
        boolean added = database.call("cannot allow chat " + chatId, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert or ignore into allowed_chats (chat_id, added_at, added_by, note) "
                            + "values (?, ?, ?, ?)")) {
                ps.setLong(1, chatId);
                ps.setLong(2, Instant.now().toEpochMilli());
                ps.setLong(3, addedBy);
                ps.setString(4, note == null || note.isBlank() ? null : note.strip());
                return ps.executeUpdate() > 0;
            }
        });
        reload();
        return added;
    }

    /** @return false when that chat was not on the list to begin with */
    public boolean deny(long chatId) {
        boolean removed = database.call("cannot deny chat " + chatId, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "delete from allowed_chats where chat_id = ?")) {
                ps.setLong(1, chatId);
                return ps.executeUpdate() > 0;
            }
        });
        reload();
        return removed;
    }

    public List<Entry> all() {
        return database.callQuietly("cannot read the access list", connection -> {
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery(
                         "select * from allowed_chats order by added_at")) {
                List<Entry> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(new Entry(rs.getLong("chat_id"),
                            Instant.ofEpochMilli(rs.getLong("added_at")),
                            rs.getLong("added_by"), rs.getString("note")));
                }
                return entries;
            }
        }, List.of());
    }

    public List<Long> owners() {
        return owners;
    }

    private void reload() {
        allowed = all().stream().map(Entry::chatId).toList();
    }
}
