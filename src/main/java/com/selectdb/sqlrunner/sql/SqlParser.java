package com.selectdb.sqlrunner.sql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlParser {

    private static final Pattern SELECT_PATTERN = Pattern.compile("(?i)^(\\s*SELECT\\s+)");

    public record SqlTask(
            String uuid,
            String originalSql,
            String taggedSql,
            Path sourceFile,
            int statementIndex,
            int statementCount) {}

    public List<SqlTask> parse(Path sqlFile) throws IOException {
        String content = Files.readString(sqlFile);
        content = stripComments(content);
        String[] parts = content.split(";");

        List<SqlTask> tasks = new ArrayList<>();
        for (String part : parts) {
            String sql = part.trim();
            if (sql.isEmpty()) continue;
            String uuid = UUID.randomUUID().toString().replace("-", "");
            String taggedSql = injectHint(sql, uuid);
            tasks.add(new SqlTask(uuid, sql, taggedSql, sqlFile, tasks.size() + 1, 0));
        }
        return withStatementCounts(tasks);
    }

    private List<SqlTask> withStatementCounts(List<SqlTask> tasks) {
        List<SqlTask> result = new ArrayList<>(tasks.size());
        int count = tasks.size();
        for (SqlTask task : tasks) {
            result.add(new SqlTask(
                    task.uuid(),
                    task.originalSql(),
                    task.taggedSql(),
                    task.sourceFile(),
                    task.statementIndex(),
                    count));
        }
        return result;
    }

    private String stripComments(String sql) {
        // remove block comments /* ... */ but preserve optimizer hints /*+ ... */
        sql = sql.replaceAll("/\\*(?!\\+).*?\\*/", "");
        // remove line comments -- ...
        sql = sql.replaceAll("--[^\n]*", "");
        return sql;
    }

    private String injectHint(String sql, String uuid) {
        String hint = "/*+SET_VAR(session_context=trace_id:" + uuid + ")*/ ";
        Matcher m = SELECT_PATTERN.matcher(sql);
        if (m.find()) {
            return sql.substring(0, m.end()) + hint + sql.substring(m.end());
        }
        return hint + sql;
    }
}
