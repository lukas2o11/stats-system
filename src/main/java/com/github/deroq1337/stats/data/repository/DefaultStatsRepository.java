package com.github.deroq1337.stats.data.repository;

import com.github.deroq1337.stats.data.database.MySQL;
import com.github.deroq1337.stats.data.entity.Stats;
import com.github.deroq1337.stats.data.models.stat.ImmutableStat;
import com.github.deroq1337.stats.data.models.stat.Stat;
import com.github.deroq1337.stats.data.models.stat.StatType;
import com.github.deroq1337.stats.data.models.top10.TopTenList;
import com.github.deroq1337.stats.data.models.top10.TopTenListEntry;
import com.github.deroq1337.stats.data.database.result.DBResult;
import com.github.deroq1337.stats.data.database.result.DBRow;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class DefaultStatsRepository implements StatsRepository {

    /* Too lazy to create config */
    private static final StatType RANK_SORT_BY = StatType.KILLS;

    private static final String STATS_QUERY = "SELECT stats_users.player, stats_users.stat, stats_users.value, stats_users.timestamp, stats.locale_key " +
            "FROM stats_users " +
            "INNER JOIN stats ON stats.id = stats_users.stat " +
            "WHERE stats_users.player = ? " +
            "AND stats_users.timestamp <= ? " +
            "AND stats_users.timestamp > ?";

    private static final String RANK_QUERY = "SELECT COUNT(*) + 1 AS rank " +
            "FROM stats_users " +
            "WHERE stat = '" + RANK_SORT_BY + "' " +
            "AND timestamp <= ? " +
            "AND timestamp > ? " +
            "AND value > (SELECT SUM(value) FROM stats_users WHERE player = ? AND stat = '" + RANK_SORT_BY + "')";

    private static final String TOP_LIST_INTERVAL_QUERY = "SELECT player, SUM(value) AS total_value " +
            "FROM stats_users " +
            "WHERE stat = '" + RANK_SORT_BY + "' " +
            "AND timestamp <= ? " +
            "AND timestamp > ? " +
            "GROUP BY player " +
            "ORDER BY total_value DESC " +
            "LIMIT 10;";

    private final @NotNull MySQL mySQL;

    public DefaultStatsRepository(@NotNull MySQL mySQL) {
        this.mySQL = mySQL;
        createTablesAndIndices();
    }

    private void createTablesAndIndices() {
        mySQL.update("CREATE TABLE IF NOT EXISTS stats(" +
                "id VARCHAR(32) NOT NULL," +
                "locale_key VARCHAR(32) NOT NULL," +
                "description VARCHAR(64)," +
                "PRIMARY KEY(id)" +
                ");").join();

        mySQL.update("CREATE TABLE IF NOT EXISTS stats_users(" +
                "id INT AUTO_INCREMENT NOT NULL," +
                "player VARCHAR(36) NOT NULL," +
                "stat VARCHAR(32) NOT NULL," +
                "value INT NOT NULL," +
                "timestamp BIGINT NOT NULL," +
                "PRIMARY KEY(id)," +
                "FOREIGN KEY(stat) REFERENCES stats(id)" +
                ");").join();

        mySQL.update("CREATE INDEX IF NOT EXISTS idx_stats_users_player_timestamp ON stats_users(player, timestamp);");
        mySQL.update("CREATE INDEX IF NOT EXISTS idx_stats_users_stat ON stats_users(stat);");
        mySQL.update("CREATE INDEX IF NOT EXISTS idx_stats_users_value ON stats_users(value);");
        mySQL.update("CREATE INDEX IF NOT EXISTS idx_stats_users_value_timestamp ON stats_users(value);");
    }

    @Override
    public @NotNull CompletableFuture<Stats> getStatsByPlayer(@NotNull UUID player) {
        return getStatsByPlayer(player, Integer.MAX_VALUE);
    }

    @Override
    public @NotNull CompletableFuture<Stats> getStatsByPlayer(@NotNull UUID player, int interval) {
        long now = System.currentTimeMillis();
        long millis = now - ((long) interval * 24 * 60 * 60 * 1000);

        CompletableFuture<DBResult> statsFuture = mySQL.query(STATS_QUERY, player.toString(), now, millis);
        CompletableFuture<Long> rankFuture = mySQL.query(RANK_QUERY, now, millis, player.toString()).thenApply(result -> {
            if (result.getRows().isEmpty()) {
                return (long) -1;
            }
            return result.getRows().getFirst().getValue("rank", Long.class);
        });

        return CompletableFuture.allOf(statsFuture, rankFuture).thenApplyAsync(v -> {
            DBResult statsResult = statsFuture.join();
            Map<StatType, Stat> statMap = new HashMap<>();

            statsResult.getRows().forEach(row -> {
                StatType type = StatType.fromString(row.getValue("stat", String.class).toUpperCase(Locale.ENGLISH));
                String localeKey = row.getValueOptional("locale_key", String.class).orElse("N/A");
                int value = row.getValueOptional("value", Integer.class).orElse(0);

                statMap.computeIfAbsent(type, k -> new Stat(type, localeKey))
                        .incrementValue(value);
            });

            Set<ImmutableStat> statSet = statMap.values().stream()
                    .map(stat -> new ImmutableStat(stat.getType(), stat.getLocaleKey(), stat.getValue()))
                    .collect(Collectors.toSet());
            return new Stats(player, interval, rankFuture.join(), statSet);
        });
    }

    @Override
    public @NotNull CompletableFuture<TopTenList> getTopTenList() {
        return getTopTenList(Integer.MAX_VALUE);
    }

    @Override
    public @NotNull CompletableFuture<TopTenList> getTopTenList(int interval) {
        long now = System.currentTimeMillis();
        long millis = now - ((long) interval * 24 * 60 * 60 * 1000);

        return mySQL.query(TOP_LIST_INTERVAL_QUERY, now, millis).thenApply(result -> {
            Set<TopTenListEntry> entries = new LinkedHashSet<>();
            int rank = 1;
            for (DBRow row : result.getRows()) {
                UUID player = UUID.fromString(row.getValue("player", String.class));
                long value = row.getValueOptional("total_value", BigDecimal.class)
                        .map(BigDecimal::longValue)
                        .orElse((long) 0);

                entries.add(new TopTenListEntry(player, rank, value));
                rank++;
            }

            return new TopTenList(interval, entries);
        }).exceptionally(throwable -> {
            System.err.println("Error query for top 10: " + throwable.getMessage());
            throw new RuntimeException(throwable);
        });
    }
}
