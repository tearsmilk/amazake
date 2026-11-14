package core.amazake.core.framework;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The shared embedded database of AmazakeCore (homes, warps, groups, holograms…). A single file in the system's
 * data folder, through the pure-Java H2 driver bundled in the .azs — no native library, so the system unloads and
 * reloads cleanly (a native JDBC driver cannot be reloaded in a new class loader).
 *
 * <p>All access runs on one owned thread ("AmazakeCore DB"), so statements never touch the tick thread and are
 * serialized without locks. Call {@link #read}/{@link #write} from anywhere; they return a {@link CompletableFuture}
 * — apply the result back to a player with {@code context.runOnceForPlayer}. The driver is used directly (not through
 * {@code DriverManager}), so nothing keeps a reference to this class loader after {@link #close()}.</p>
 */
public final class Database implements AutoCloseable {

    /** A unit of work against the open connection. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Maps one row; use with {@link #queryList}. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private final Logger logger;
    private final ExecutorService executor;
    private final Connection connection;

    private Database(final Logger logger, final ExecutorService executor, final Connection connection) {
        this.logger = logger;
        this.executor = executor;
        this.connection = connection;
    }

    /** Opens (or creates) the database file {@code <dir>/amazakecore.mv.db}. Must be called off the tick thread. */
    public static Database open(final Path dir, final Logger logger) throws SQLException {
        final ThreadFactory factory = runnable -> {
            final Thread thread = new Thread(runnable, "AmazakeCore DB");
            thread.setDaemon(true);
            return thread;
        };
        final ExecutorService executor = Executors.newSingleThreadExecutor(factory);
        final String url = "jdbc:h2:file:" + dir.resolve("amazakecore").toAbsolutePath()
            + ";MODE=LEGACY;DB_CLOSE_ON_EXIT=FALSE;AUTO_RECONNECT=TRUE";
        final Properties props = new Properties();
        // The H2 driver is loaded from our own jar; use it directly so DriverManager keeps no reference to us.
        final Connection connection;
        try {
            connection = new org.h2.Driver().connect(url, props);
        } catch (final SQLException e) {
            executor.shutdownNow();
            throw e;
        }
        connection.setAutoCommit(true);
        return new Database(logger, executor, connection);
    }

    /** Runs read/write work on the database thread. */
    public <T> CompletableFuture<T> submit(final Work<T> work) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        this.executor.execute(() -> {
            try {
                future.complete(work.run(this.connection));
            } catch (final Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** A write (INSERT/UPDATE/DELETE/DDL) with positional parameters; completes with the affected row count. */
    public CompletableFuture<Integer> write(final String sql, final Object... params) {
        return this.submit(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    /** A query mapping each row with {@code mapper}. */
    public <T> CompletableFuture<List<T>> queryList(final String sql, final RowMapper<T> mapper, final Object... params) {
        return this.submit(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    final List<T> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                    return out;
                }
            }
        });
    }

    /** The first mapped row, or null. */
    public <T> CompletableFuture<T> queryFirst(final String sql, final RowMapper<T> mapper, final Object... params) {
        return this.queryList(sql, mapper, params).thenApply(list -> list.isEmpty() ? null : list.get(0));
    }

    /** Runs DDL now, on the calling thread's behalf via the DB thread, and waits — for table creation in onEnable. */
    public void ddl(final String... statements) throws SQLException {
        try {
            this.submit(connection -> {
                try (var st = connection.createStatement()) {
                    for (final String sql : statements) {
                        st.execute(sql);
                    }
                }
                return null;
            }).get(30, TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new SQLException("DDL failed: " + e.getMessage(), e);
        }
    }

    private static void bind(final PreparedStatement ps, final Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    public <T> T mapOrNull(final ResultSet rs, final Function<ResultSet, T> f) {
        return rs == null ? null : f.apply(rs);
    }

    @Override
    public void close() {
        this.executor.execute(() -> {
            try {
                if (!this.connection.isClosed()) {
                    try (var st = this.connection.createStatement()) {
                        st.execute("SHUTDOWN");
                    } catch (final SQLException ignored) {
                        // SHUTDOWN closes the store; a follow-up error here is harmless.
                    }
                    this.connection.close();
                }
            } catch (final SQLException e) {
                this.logger.log(Level.WARNING, "AmazakeCore: ошибка при закрытии базы", e);
            }
        });
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(10, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (final InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
