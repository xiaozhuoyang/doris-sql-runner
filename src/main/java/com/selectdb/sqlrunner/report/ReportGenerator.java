package com.selectdb.sqlrunner.report;

import com.selectdb.sqlrunner.executor.SqlExecutor;
import com.selectdb.sqlrunner.profile.QueryInfoClient;
import com.selectdb.sqlrunner.sql.SqlParser;

import java.util.List;
import java.util.Map;

public class ReportGenerator {

    public static void printSummary(
            List<SqlParser.SqlTask> tasks,
            List<SqlExecutor.ExecutionResult> results,
            Map<String, QueryInfoClient.QueryInfo> queryInfoMap,
            Map<String, String> profilePaths) {

        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.printf("%-4s %-32s %-8s %-10s %-26s %-32s %-30s%n",
                "#", "UUID", "Status", "Duration", "QueryID", "FE_IP", "ProfileFile");
        System.out.println("-".repeat(120));

        for (int i = 0; i < tasks.size(); i++) {
            SqlParser.SqlTask task = tasks.get(i);
            SqlExecutor.ExecutionResult result = results.get(i);
            QueryInfoClient.QueryInfo info = queryInfoMap.get(task.uuid());

            String status = result.success() ? "OK" : "FAIL";
            String duration = result.durationMs() + "ms";
            String queryId = info != null ? info.queryId() : "-";
            String feIp = info != null ? info.feIp() : "-";
            String profileFile = profilePaths.getOrDefault(task.uuid(), "-");

            System.out.printf("%-4d %-32s %-8s %-10s %-26s %-32s %-30s%n",
                    i + 1, task.uuid(), status, duration, queryId, feIp, profileFile);
        }

        long successCount = results.stream().filter(SqlExecutor.ExecutionResult::success).count();
        System.out.println("-".repeat(120));
        System.out.printf("Total: %d, Success: %d, Failed: %d, Profiles: %d%n",
                tasks.size(), successCount, tasks.size() - successCount, profilePaths.size());
        System.out.println("=".repeat(120));
    }
}
