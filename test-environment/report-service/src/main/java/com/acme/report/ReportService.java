package com.acme.report;

import com.acme.libs.common.InternalRestTemplateClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ReportService {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final InternalRestTemplateClient http = new InternalRestTemplateClient(new RestTemplate());

    @Value("${report.base-url}")
    private String baseUrl;

    @Value("${clients.orders}")
    private String ordersUrl;

    public ReportService(JdbcTemplate jdbcTemplate, JdbcClient jdbcClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = jdbcClient;
    }

    public List<Map<String, Object>> build(String type, String tenant) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int page = 0;
        List<Map<String, Object>> chunk;
        do {
            chunk = jdbcTemplate.queryForList("SELECT * FROM report_rows WHERE type = ? LIMIT 100 OFFSET ?", type, page * 100);
            rows.addAll(chunk);
            page++;
        } while (!chunk.isEmpty());

        rows.stream()
                .filter(r -> r.get("orderId") != null)
                .forEach(r -> http.fetch(ordersUrl + "/orders/" + r.get("orderId"), Map.class));

        String source = "daily".equals(type) ? ordersUrl + "/orders" : ordersUrl + "/orders/search";
        http.fetch(source, List.class);

        jdbcClient.sql("INSERT INTO report_runs (type, tenant) VALUES (:type, :tenant)")
                .param("type", type)
                .param("tenant", tenant)
                .update();
        return rows;
    }

    public void export(String tenant, String target) {
        // URL built at runtime from config + request data: only resolvable via resolutions.yml callSites
        String url = baseUrl + "/" + tenant + "/x";
        try {
            http.exchange(HttpMethod.PUT, url, Map.of("tenant", tenant), Void.class);
        } catch (RuntimeException e) {
            jdbcTemplate.update("UPDATE report_runs SET status = 'FAILED' WHERE tenant = ?", tenant);
        }
        // fully dynamic target from the request body
        http.send(target, Map.of("tenant", tenant), Void.class);
        jdbcTemplate.update("DELETE FROM report_tmp WHERE tenant = ?", tenant);
    }
}
