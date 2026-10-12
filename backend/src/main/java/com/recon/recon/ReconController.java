package com.recon.recon;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ReconController {

    public record ManualMatch(long statementLineId, long entryId) {
    }

    private final StatementService statements;
    private final MatchingService matching;
    private final ExceptionAssistant assistant;
    private final NamedParameterJdbcTemplate jdbc;

    public ReconController(StatementService statements, MatchingService matching,
                           ExceptionAssistant assistant, NamedParameterJdbcTemplate jdbc) {
        this.statements = statements;
        this.matching = matching;
        this.assistant = assistant;
        this.jdbc = jdbc;
    }

    @PostMapping("/statements")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The file is empty");
        }
        return statements.ingest(file.getOriginalFilename() == null ? "statement.csv" : file.getOriginalFilename(),
                file.getInputStream());
    }

    @GetMapping("/statements")
    public List<Map<String, Object>> listStatements() {
        return jdbc.queryForList("""
                select b.id, b.filename, b.uploaded_at,
                       count(l.id) as lines,
                       count(l.id) filter (where l.status = 'MATCHED') as matched,
                       count(l.id) filter (where l.status = 'UNMATCHED') as unmatched
                  from statement_batches b
                  left join bank_statement_lines l on l.batch_id = b.id
                 group by b.id
                 order by b.id desc
                """, Map.of());
    }

    @PostMapping("/statements/{id}/reconcile")
    public MatchingService.Summary reconcile(@PathVariable long id) {
        return matching.reconcile(id);
    }

    @GetMapping("/statements/{id}/lines")
    public List<Map<String, Object>> lines(@PathVariable long id) {
        return jdbc.queryForList("""
                select l.id, l.txn_date, l.amount, l.description, l.reference, l.status,
                       m.method, m.confidence, m.entry_id
                  from bank_statement_lines l
                  left join matches m on m.statement_line_id = l.id
                 where l.batch_id = :id
                 order by l.id
                """, Map.of("id", id));
    }

    @GetMapping("/exceptions")
    public Map<String, Object> exceptions() {
        List<Map<String, Object>> lines = jdbc.queryForList("""
                select id, batch_id, txn_date, amount, description, reference
                  from bank_statement_lines where status = 'UNMATCHED' order by id limit 300
                """, Map.of());

        Map<Long, List<Map<String, Object>>> flags = new HashMap<>();
        jdbc.query("select statement_line_id, kind, detail from anomalies where statement_line_id is not null",
                (RowCallbackHandler) rs -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("kind", rs.getString("kind"));
                    f.put("detail", rs.getString("detail"));
                    flags.computeIfAbsent(rs.getLong("statement_line_id"), k -> new ArrayList<>()).add(f);
                });

        List<Map<String, Object>> withFlags = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            Map<String, Object> copy = new LinkedHashMap<>(l);
            copy.put("flags", flags.getOrDefault(((Number) l.get("id")).longValue(), List.of()));
            withFlags.add(copy);
        }

        List<Map<String, Object>> missing = jdbc.queryForList("""
                select a.entry_id, a.detail, e.reference, e.description, b.entry_date, b.bank_amount
                  from anomalies a
                  join journal_entries e on e.id = a.entry_id
                  join bank_side_entries b on b.entry_id = a.entry_id
                 where a.kind = 'MISSING_SETTLEMENT'
                 order by b.entry_date
                """, Map.of());

        return Map.of("unmatchedLines", withFlags, "missingSettlements", missing);
    }

    @PostMapping("/exceptions/{lineId}/suggest")
    public Map<String, Object> suggest(@PathVariable long lineId) {
        return assistant.suggest(lineId);
    }

    @PostMapping("/exceptions/{lineId}/ignore")
    public Map<String, String> ignore(@PathVariable long lineId) {
        matching.ignore(lineId);
        return Map.of("status", "ignored");
    }

    @PostMapping("/matches/manual")
    public Map<String, String> manual(@RequestBody ManualMatch body) {
        matching.manualMatch(body.statementLineId(), body.entryId());
        return Map.of("status", "matched");
    }
}
