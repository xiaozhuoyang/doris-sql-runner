package com.selectdb.sqlrunner.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.selectdb.sqlrunner.config.RunnerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class QueryInfoClient {

    private static final Logger log = LoggerFactory.getLogger(QueryInfoClient.class);

    public record QueryInfo(String queryId, String feIp) {}

    private final HttpClient httpClient;
    private final String authHeader;
    private final String feHttpUrl;

    public QueryInfoClient(RunnerConfig config) {
        this.httpClient = HttpClient.newHttpClient();
        String credentials = Base64.getEncoder().encodeToString(
                (config.user() + ":" + config.password()).getBytes());
        this.authHeader = "Basic " + credentials;
        this.feHttpUrl = "http://%s:%d".formatted(config.host(), config.feHttpPort());
    }

    public Map<String, QueryInfo> fetchQueryInfo(Map<String, String> uuidToTraceId) throws Exception {
        String url = feHttpUrl + "/rest/v2/manager/query/query_info?is_all_node=true";
        log.info("Fetching query_info from {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch query_info: HTTP " + response.statusCode());
        }

        return parseQueryInfo(response.body(), uuidToTraceId);
    }

    private Map<String, QueryInfo> parseQueryInfo(String json, Map<String, String> uuidToTraceId) {
        Map<String, QueryInfo> result = new HashMap<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        JsonArray rows = data.getAsJsonArray("rows");

        for (JsonElement row : rows) {
            JsonArray arr = row.getAsJsonArray();
            String queryId = arr.get(0).getAsString();
            String feIp = arr.get(1).getAsString();
            String sql = arr.get(4).getAsString();

            // Debug: print first 200 chars of each SQL from query_info
            log.debug("query_info row: queryId={}, sql={}", queryId,
                    sql.substring(0, Math.min(200, sql.length())));

            for (Map.Entry<String, String> entry : uuidToTraceId.entrySet()) {
                String uuid = entry.getKey();
                if (sql.contains("trace_id:" + uuid)) {
                    result.put(uuid, new QueryInfo(queryId, feIp));
                    log.info("Matched uuid={} -> queryId={}, feIp={}", uuid, queryId, feIp);
                    break;
                }
            }
        }

        if (result.isEmpty()) {
            log.warn("No query_id matched for any trace_id. Total rows in query_info: {}", rows.size());
            // Print all SQLs from query_info for debugging
            for (JsonElement row : rows) {
                JsonArray arr = row.getAsJsonArray();
                log.warn("  queryId={}, sql={}", arr.get(0).getAsString(),
                        arr.get(4).getAsString().substring(0, Math.min(120, arr.get(4).getAsString().length())));
            }
        }
        return result;
    }
}
