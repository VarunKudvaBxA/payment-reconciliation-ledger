package com.recon.recon;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final NamedParameterJdbcTemplate jdbc;
    private final AnomalyService anomalies;

    public DashboardController(NamedParameterJdbcTemplate jdbc, AnomalyService anomalies) {
        this.jdbc = jdbc;
        this.anomalies = anomalies;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> totals = jdbc.queryForMap("""
                select count(*) as total,
                       count(*) filter (where status = 'MATCHED') as matched,
                       count(*) filter (where status = 'UNMATCHED') as unmatched,
                       count(*) filter (where status = 'IGNORED') as ignored
                  from bank_statement_lines
                """, Map.of());

        long total = ((Number) totals.get("total")).longValue();
        long matched = ((Number) totals.get("matched")).longValue();

        List<Map<String, Object>> byMethod = new ArrayList<>();
        jdbc.query("select method, count(*) as n from matches group by method order by method",
                (RowCallbackHandler) rs -> {
                    byMethod.add(Map.of("method", rs.getString("method"), "count", rs.getInt("n")));
                });

        List<Map<String, Object>> flagRows = new ArrayList<>();
        anomalies.countsByKind().forEach((k, v) -> flagRows.add(Map.of("kind", k, "count", v)));

        Map<String, Object> out = new LinkedHashMap<>(totals);
        out.put("matchRate", total == 0 ? 0 : Math.round((double) matched / total * 1000.0) / 1000.0);
        out.put("byMethod", byMethod);
        out.put("anomalies", flagRows);
        out.put("ledgerEntries", jdbc.queryForObject("select count(*) from journal_entries", Map.of(), Long.class));
        return out;
    }
}
