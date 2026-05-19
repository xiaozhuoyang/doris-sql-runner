package com.selectdb.sqlrunner.executor;

import com.selectdb.sqlrunner.config.RunnerConfig;
import com.selectdb.sqlrunner.sql.SqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RunnerExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunnerExecutor.class);

    private final RunnerConfig config;
    private final SqlExecutor executor;

    public RunnerExecutor(RunnerConfig config) {
        this.config = config;
        this.executor = new SqlExecutor(config);
    }

    public List<SqlExecutor.ExecutionResult> execute(List<SqlParser.SqlTask> tasks) throws Exception {
        int parallelism = config.parallelism();
        if (parallelism <= 1) {
            return executeSerial(tasks);
        } else {
            return executeParallel(tasks, parallelism);
        }
    }

    private List<SqlExecutor.ExecutionResult> executeSerial(List<SqlParser.SqlTask> tasks) {
        List<SqlExecutor.ExecutionResult> results = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            SqlParser.SqlTask task = tasks.get(i);
            log.info("Executing [{}/{}]: uuid={}", i + 1, tasks.size(), task.uuid());
            results.add(executor.execute(task.uuid(), task.taggedSql()));
            sleepBetweenQueries(i, tasks.size());
        }
        return results;
    }

    private List<SqlExecutor.ExecutionResult> executeParallel(List<SqlParser.SqlTask> tasks, int parallelism) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<SqlExecutor.ExecutionResult>> futures = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                SqlParser.SqlTask task = tasks.get(i);
                futures.add(pool.submit(() -> executor.execute(task.uuid(), task.taggedSql())));
                sleepBetweenQueries(i, tasks.size());
            }
            List<SqlExecutor.ExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                log.info("Waiting for [{}/{}]: uuid={}", i + 1, tasks.size(), tasks.get(i).uuid());
                results.add(futures.get(i).get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private void sleepBetweenQueries(int currentIndex, int total) {
        long sleepMs = config.sleepMs();
        if (sleepMs <= 0 || currentIndex >= total - 1) {
            return;
        }
        try {
            log.info("Sleeping {}ms before next query", sleepMs);
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while sleeping between queries", e);
        }
    }
}
