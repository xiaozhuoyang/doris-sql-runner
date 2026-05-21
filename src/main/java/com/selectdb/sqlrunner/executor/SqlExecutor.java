package com.selectdb.sqlrunner.executor;

import com.selectdb.sqlrunner.config.RunnerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

public class SqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutor.class);

    public record ExecutionResult(String uuid, boolean success, long durationMs, String error) {}

    private final RunnerConfig config;
    private final String jdbcUrl;

    public SqlExecutor(RunnerConfig config) {
        this.config = config;
        this.jdbcUrl = "jdbc:mysql://%s:%d/%s".formatted(config.host(), config.port(), config.database());
    }

    public ExecutionResult execute(String uuid, String taggedSql) {
        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.user(), config.password());
            Statement stmt = conn.createStatement()) {
            applySessionVariables(stmt);
            applyCluster(stmt);
            stmt.execute(taggedSql);
            long duration = System.currentTimeMillis() - start;
            log.info("[{}] executed in {}ms", uuid, duration);
            return new ExecutionResult(uuid, true, duration, null);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String errMsg = e.getMessage();
            log.error("[{}] failed in {}ms: {}", uuid, duration, errMsg);
            return new ExecutionResult(uuid, false, duration, errMsg);
        }
    }

    private void applySessionVariables(Statement stmt) throws Exception {
        for (Map.Entry<String, String> entry : config.sessionVariables().entrySet()) {
            String sql = "set " + entry.getKey() + "=" + entry.getValue();
            log.debug("Applying session variable: {}", sql);
            stmt.execute(sql);
        }
    }

    private void applyCluster(Statement stmt) throws Exception {
        if (config.cluster() == null || config.cluster().isBlank()) {
            return;
        }
        String sql = "USE @" + config.cluster();
        log.debug("Applying cluster: {}", sql);
        stmt.execute(sql);
    }
}
