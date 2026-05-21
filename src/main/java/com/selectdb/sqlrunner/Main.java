package com.selectdb.sqlrunner;

import com.selectdb.sqlrunner.config.RunnerConfig;
import com.selectdb.sqlrunner.executor.RunnerExecutor;
import com.selectdb.sqlrunner.executor.SqlExecutor;
import com.selectdb.sqlrunner.profile.ProfileClient;
import com.selectdb.sqlrunner.profile.QueryInfoClient;
import com.selectdb.sqlrunner.report.ReportGenerator;
import com.selectdb.sqlrunner.sql.SqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = "doris-sql-runner", mixinStandardHelpOptions = true,
        description = "Execute SQL file against Doris and collect profiles")
public class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = "--sql-file", description = "SQL file path (semicolon-separated)")
    private String sqlFile;

    @Option(names = "--sql-dir", description = "SQL directory path. Files are traversed recursively and executed in path order.")
    private String sqlDir;

    @Option(names = "--host", required = true, description = "Doris FE host (JDBC)")
    private String host;

    @Option(names = "--port", defaultValue = "9030", description = "Doris FE JDBC port")
    private int port;

    @Option(names = "--user", defaultValue = "root", description = "Doris user")
    private String user;

    @Option(names = "--password", defaultValue = "", description = "Doris password")
    private String password;

    @Option(names = "--database", required = true, description = "Doris database")
    private String database;

    @Option(names = "--cluster", description = "Doris compute cluster name. Runs USE @cluster before each SQL statement.")
    private String cluster;

    @Option(names = "--parallelism", defaultValue = "1", description = "Parallelism (1=serial)")
    private int parallelism;

    @Option(names = "--fe-http-port", defaultValue = "8030", description = "Doris FE HTTP port")
    private int feHttpPort;

    @Option(names = "--output-dir", defaultValue = ".", description = "Profile output directory")
    private String outputDir;

    @Option(names = "--session-var", description = "Doris session variable in key=value form. Can be repeated. enable_profile=true is set by default.")
    private List<String> sessionVarOptions = new ArrayList<>();

    @Option(names = "--sleep-ms", defaultValue = "0", description = "Sleep interval in milliseconds between SQL queries. In parallel mode this delays task submission.")
    private long sleepMs;

    @Override
    public Integer call() throws Exception {
        if (sleepMs < 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--sleep-ms must be >= 0");
        }
        validateSqlInput();
        validateCluster();
        Map<String, String> sessionVariables = parseSessionVariables(sessionVarOptions);
        RunnerConfig config = new RunnerConfig(
                sqlFile, sqlDir, host, port, user, password, database, cluster, parallelism, feHttpPort, outputDir, sessionVariables, sleepMs);

        // 1. Parse SQL input
        SqlParser parser = new SqlParser();
        List<SqlParser.SqlTask> tasks = parseSqlTasks(parser, config);
        log.info("Parsed {} SQL statements", tasks.size());

        for (SqlParser.SqlTask task : tasks) {
            System.out.printf("  [%s] %s #%d/%d %s%n",
                    task.uuid(),
                    task.sourceFile().getFileName(),
                    task.statementIndex(),
                    task.statementCount(),
                    task.originalSql().substring(0,
                    Math.min(80, task.originalSql().length())).replace("\n", " "));
        }

        // 2. Execute SQLs
        RunnerExecutor executor = new RunnerExecutor(config);
        List<SqlExecutor.ExecutionResult> results = executor.execute(tasks);

        // 3. Fetch query_info and match trace_id -> query_id
        System.out.println("\nWaiting 3s for profiles to be generated...");
        Thread.sleep(3000);

        Map<String, String> uuidToTraceId = new HashMap<>();
        for (SqlParser.SqlTask task : tasks) {
            uuidToTraceId.put(task.uuid(), task.uuid());
        }

        QueryInfoClient queryInfoClient = new QueryInfoClient(config);
        Map<String, QueryInfoClient.QueryInfo> queryInfoMap = queryInfoClient.fetchQueryInfo(uuidToTraceId);

        // 4. Fetch profiles
        ProfileClient profileClient = new ProfileClient(config);
        Map<String, String> profilePaths = new HashMap<>();
        for (Map.Entry<String, QueryInfoClient.QueryInfo> entry : queryInfoMap.entrySet()) {
            String uuid = entry.getKey();
            QueryInfoClient.QueryInfo info = entry.getValue();
            try {
                SqlParser.SqlTask task = findTaskByUuid(tasks, uuid);
                Path path = profileClient.fetchAndSaveProfile(info.queryId(), info.feIp(), task);
                profilePaths.put(uuid, path.toString());
            } catch (Exception e) {
                log.error("[{}] failed to fetch profile: {}", uuid, e.getMessage());
            }
        }

        // 5. Print summary
        ReportGenerator.printSummary(tasks, results, queryInfoMap, profilePaths);

        return 0;
    }

    private void validateSqlInput() {
        boolean hasFile = sqlFile != null && !sqlFile.isBlank();
        boolean hasDir = sqlDir != null && !sqlDir.isBlank();
        if (hasFile == hasDir) {
            throw new CommandLine.ParameterException(new CommandLine(this), "exactly one of --sql-file or --sql-dir is required");
        }
    }

    private void validateCluster() {
        if (cluster == null || cluster.isBlank()) {
            return;
        }
        if (!cluster.matches("[A-Za-z0-9_\\-]+")) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--cluster contains invalid characters: " + cluster);
        }
    }

    private List<SqlParser.SqlTask> parseSqlTasks(SqlParser parser, RunnerConfig config) throws Exception {
        if (config.sqlFile() != null && !config.sqlFile().isBlank()) {
            Path path = Path.of(config.sqlFile());
            List<SqlParser.SqlTask> tasks = parser.parse(path);
            log.info("Parsed {} SQL statements from {}", tasks.size(), path);
            return tasks;
        }

        Path dir = Path.of(config.sqlDir());
        List<Path> files;
        try (Stream<Path> stream = java.nio.file.Files.walk(dir)) {
            files = stream
                    .filter(java.nio.file.Files::isRegularFile)
                    .sorted()
                    .toList();
        }
        if (files.isEmpty()) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--sql-dir has no regular files: " + dir);
        }

        List<SqlParser.SqlTask> tasks = new ArrayList<>();
        for (Path file : files) {
            List<SqlParser.SqlTask> fileTasks = parser.parse(file);
            log.info("Parsed {} SQL statements from {}", fileTasks.size(), file);
            tasks.addAll(fileTasks);
        }
        return tasks;
    }

    private SqlParser.SqlTask findTaskByUuid(List<SqlParser.SqlTask> tasks, String uuid) {
        for (SqlParser.SqlTask task : tasks) {
            if (task.uuid().equals(uuid)) {
                return task;
            }
        }
        return null;
    }

    private Map<String, String> parseSessionVariables(List<String> options) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("enable_profile", "true");
        if (options == null) {
            return variables;
        }
        for (String option : options) {
            if (option == null || option.isBlank()) {
                continue;
            }
            int split = option.indexOf('=');
            if (split <= 0) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "--session-var must use key=value format: " + option);
            }
            String key = option.substring(0, split).trim();
            String value = option.substring(split + 1).trim();
            if (key.isEmpty()) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "--session-var key must not be empty: " + option);
            }
            variables.put(key, value);
        }
        return variables;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
