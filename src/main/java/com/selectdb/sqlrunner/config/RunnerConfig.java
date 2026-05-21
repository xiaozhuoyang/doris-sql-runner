package com.selectdb.sqlrunner.config;

import java.util.Map;

public record RunnerConfig(
        String sqlFile,
        String sqlDir,
        String host,
        int port,
        String user,
        String password,
        String database,
        String cluster,
        int parallelism,
        int feHttpPort,
        String outputDir,
        Map<String, String> sessionVariables,
        long sleepMs
) {}
