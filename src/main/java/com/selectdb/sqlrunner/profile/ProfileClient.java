package com.selectdb.sqlrunner.profile;

import com.selectdb.sqlrunner.config.RunnerConfig;
import com.selectdb.sqlrunner.sql.SqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

public class ProfileClient {

    private static final Logger log = LoggerFactory.getLogger(ProfileClient.class);

    private final HttpClient httpClient;
    private final String authHeader;
    private final Path outputDir;

    public ProfileClient(RunnerConfig config) {
        this.httpClient = HttpClient.newHttpClient();
        String credentials = Base64.getEncoder().encodeToString(
                (config.user() + ":" + config.password()).getBytes());
        this.authHeader = "Basic " + credentials;
        this.outputDir = Path.of(config.outputDir());
    }

    public Path fetchAndSaveProfile(String queryId, String feIp) throws Exception {
        return fetchAndSaveProfile(queryId, feIp, null);
    }

    public Path fetchAndSaveProfile(String queryId, String feIp, SqlParser.SqlTask task) throws Exception {
        String url = "http://%s/api/profile?query_id=%s".formatted(feIp, queryId);
        log.info("Fetching profile: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch profile: HTTP " + response.statusCode());
        }

        Files.createDirectories(outputDir);
        Path outputFile = outputDir.resolve(profileFilename(queryId, task));
        Files.writeString(outputFile, response.body());
        log.info("Profile saved to {}", outputFile);
        return outputFile;
    }

    private String profileFilename(String queryId, SqlParser.SqlTask task) {
        if (task == null || task.sourceFile() == null) {
            return queryId + ".profile";
        }
        String fileName = task.sourceFile().getFileName().toString();
        if (task.statementCount() <= 1) {
            return fileName + ".profile";
        }
        return "%s.%03d.profile".formatted(fileName, task.statementIndex());
    }

    public void fetchAll(Map<String, QueryInfoClient.QueryInfo> queryInfoMap) {
        for (Map.Entry<String, QueryInfoClient.QueryInfo> entry : queryInfoMap.entrySet()) {
            String uuid = entry.getKey();
            QueryInfoClient.QueryInfo info = entry.getValue();
            try {
                Path path = fetchAndSaveProfile(info.queryId(), info.feIp());
                log.info("[{}] profile saved: {}", uuid, path);
            } catch (Exception e) {
                log.error("[{}] failed to fetch profile: {}", uuid, e.getMessage());
            }
        }
    }
}
