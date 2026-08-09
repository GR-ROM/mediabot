package su.grinev.mediabot.links;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.db.Database;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ShortLinkRepository {

    private final Database database;

    @PostConstruct
    void createTable() {
        database.call("cannot create the links table", connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("""
                        create table if not exists links (
                          code       text    primary key,
                          job_id     integer not null,
                          chat_id    integer not null,
                          file_path  text    not null,
                          file_name  text    not null,
                          size_bytes integer not null,
                          created_at integer not null,
                          expires_at integer not null
                        )""");
                s.execute("create index if not exists links_expiry on links(expires_at)");

                // A link is retired in two moves: one pass writes this column, a later one deletes
                // the bytes. Kept apart because the first is a row update that has to be exact and
                // the second is filesystem work that may fail, take a while, or be interrupted —
                // and a sweep that dies halfway must not leave rows claiming to be live.
                if (!hasColumn(connection, "state")) {
                    s.execute("alter table links add column state text not null default 'LIVE'");
                }
                s.execute("create index if not exists links_state on links(state)");
            }
            return null;
        });
    }

    private static boolean hasColumn(java.sql.Connection connection, String name)
            throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("pragma table_info(links)")) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Marks everything past its hour, in one statement.
     *
     * @return how many links stopped working
     */
    public int expireOverdue(Instant moment) {
        return database.call("cannot expire the overdue links", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "update links set state = 'EXPIRED' where state = 'LIVE' and expires_at <= ?")) {
                ps.setLong(1, moment.toEpochMilli());
                return ps.executeUpdate();
            }
        });
    }

    public List<ShortLink> findExpired(int limit) {
        return database.callQuietly("cannot read the expired links", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "select * from links where state = 'EXPIRED' order by expires_at limit ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ShortLink> links = new ArrayList<>();
                    while (rs.next()) {
                        links.add(read(rs));
                    }
                    return links;
                }
            }
        }, List.of());
    }

    /** One statement for the whole batch: the rows go together or not at all. */
    public int deleteByCodes(List<String> codes) {
        if (codes.isEmpty()) {
            return 0;
        }
        String places = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        return database.call("cannot delete " + codes.size() + " link(s)", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "delete from links where code in (" + places + ")")) {
                for (int i = 0; i < codes.size(); i++) {
                    ps.setString(i + 1, codes.get(i));
                }
                return ps.executeUpdate();
            }
        });
    }

    public void save(ShortLink link) {
        database.call("cannot record the link for job " + link.jobId(), connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    insert into links (code, job_id, chat_id, file_path, file_name, size_bytes,
                                       created_at, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, link.code());
                ps.setLong(2, link.jobId());
                ps.setLong(3, link.chatId());
                ps.setString(4, link.file().toString());
                ps.setString(5, link.fileName());
                ps.setLong(6, link.sizeBytes());
                ps.setLong(7, link.createdAt().toEpochMilli());
                ps.setLong(8, link.expiresAt().toEpochMilli());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<ShortLink> findByCode(String code) {
        return database.callQuietly("cannot read link " + code, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "select * from links where code = ?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<ShortLink>empty();
                }
            }
        }, Optional.empty());
    }

    public Optional<ShortLink> findByJobId(long jobId) {
        return database.callQuietly("cannot read the link for job " + jobId, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "select * from links where job_id = ? order by created_at desc limit 1")) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<ShortLink>empty();
                }
            }
        }, Optional.empty());
    }

    public List<ShortLink> findAllByJobId(long jobId) {
        return database.callQuietly("cannot read the links for job " + jobId, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "select * from links where job_id = ? order by created_at, code")) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ShortLink> links = new java.util.ArrayList<>();
                    while (rs.next()) {
                        links.add(read(rs));
                    }
                    return links;
                }
            }
        }, List.of());
    }

    public List<ShortLink> findExpiredBefore(Instant moment) {
        return database.callQuietly("cannot list expired links", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "select * from links where expires_at < ?")) {
                ps.setLong(1, moment.toEpochMilli());
                List<ShortLink> links = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        links.add(read(rs));
                    }
                }
                return links;
            }
        }, List.of());
    }

    public void deleteByCode(String code) {
        database.callQuietly("cannot delete link " + code, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "delete from links where code = ?")) {
                ps.setString(1, code);
                ps.executeUpdate();
            }
            return null;
        }, null);
    }

    private static ShortLink read(ResultSet rs) throws SQLException {
        return new ShortLink(
                rs.getString("code"),
                rs.getLong("job_id"),
                rs.getLong("chat_id"),
                Path.of(rs.getString("file_path")),
                rs.getString("file_name"),
                rs.getLong("size_bytes"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("expires_at")));
    }
}
