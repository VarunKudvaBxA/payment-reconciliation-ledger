package com.recon.recon;

import com.recon.embedding.Embedder;
import com.recon.embedding.VectorUtil;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Three passes per statement line, cheapest first:
 *   1. exact    same amount and same reference
 *   2. fuzzy    same amount, date within 3 days, similar description (Jaro-Winkler)
 *   3. semantic amount within 1 percent, date within 5 days, closest description by
 *               cosine distance in pgvector
 * Every ledger entry can be matched only once (unique index on matches.entry_id).
 */
@Service
public class MatchingService {

    public record Summary(long batchId, int total, int exact, int fuzzy, int semantic, int manual,
                          int unmatched, double matchRate, int anomalies) {
    }

    private record Line(long id, LocalDate date, BigDecimal amount, String description, String reference, String embedding) {
    }

    private record Candidate(long entryId, double score) {
    }

    private static final String NOT_MATCHED = " and not exists (select 1 from matches m where m.entry_id = b.entry_id) ";

    private final NamedParameterJdbcTemplate jdbc;
    private final Embedder embedder;
    private final AnomalyService anomalies;
    private final double fuzzyThreshold;
    private final double semanticThreshold;
    private final JaroWinklerSimilarity jw = new JaroWinklerSimilarity();

    public MatchingService(NamedParameterJdbcTemplate jdbc, Embedder embedder, AnomalyService anomalies,
                           @Value("${recon.fuzzy-threshold}") double fuzzyThreshold,
                           @Value("${recon.semantic-threshold}") double semanticThreshold) {
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.anomalies = anomalies;
        this.fuzzyThreshold = fuzzyThreshold;
        this.semanticThreshold = semanticThreshold;
    }

    @Transactional
    public Summary reconcile(long batchId) {
        Integer exists = jdbc.queryForObject("select count(*) from statement_batches where id = :b",
                Map.of("b", batchId), Integer.class);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("Statement " + batchId + " does not exist");
        }

        embedMissingEntries();

        for (Line line : unmatchedLines(batchId)) {
            Optional<Candidate> hit = exact(line);
            String method = "EXACT";
            if (hit.isEmpty()) {
                hit = fuzzy(line);
                method = "FUZZY";
            }
            if (hit.isEmpty()) {
                hit = semantic(line);
                method = "SEMANTIC";
            }
            if (hit.isPresent()) {
                saveMatch(line.id(), hit.get().entryId(), method, hit.get().score());
            }
        }

        int found = anomalies.scan(batchId);
        return summary(batchId, found);
    }

    @Transactional
    public void manualMatch(long lineId, long entryId) {
        Integer open = jdbc.queryForObject(
                "select count(*) from bank_statement_lines where id = :l and status = 'UNMATCHED'",
                Map.of("l", lineId), Integer.class);
        if (open == null || open == 0) {
            throw new IllegalArgumentException("That statement line is not waiting for a match");
        }
        Integer free = jdbc.queryForObject(
                "select count(*) from bank_side_entries b where b.entry_id = :e " + NOT_MATCHED,
                Map.of("e", entryId), Integer.class);
        if (free == null || free == 0) {
            throw new IllegalArgumentException("That ledger entry does not exist or is already matched");
        }
        saveMatch(lineId, entryId, "MANUAL", 1.0);
    }

    @Transactional
    public void ignore(long lineId) {
        int changed = jdbc.update("update bank_statement_lines set status = 'IGNORED' where id = :l and status = 'UNMATCHED'",
                Map.of("l", lineId));
        if (changed == 0) {
            throw new IllegalArgumentException("That statement line is not waiting for a match");
        }
    }

    // ---- the three passes ----

    private Optional<Candidate> exact(Line l) {
        if (l.reference() == null) {
            return Optional.empty();
        }
        List<Long> ids = jdbc.queryForList(
                "select b.entry_id from bank_side_entries b where b.bank_amount = :amt and lower(b.reference) = lower(:ref) "
                        + NOT_MATCHED + " order by b.entry_id limit 1",
                new MapSqlParameterSource("amt", l.amount()).addValue("ref", l.reference()), Long.class);
        return ids.isEmpty() ? Optional.empty() : Optional.of(new Candidate(ids.get(0), 1.0));
    }

    private Optional<Candidate> fuzzy(Line l) {
        List<Candidate> rows = jdbc.query(
                "select b.entry_id, b.description from bank_side_entries b "
                        + "where abs(b.bank_amount - :amt) < 0.005 and abs(b.entry_date - cast(:d as date)) <= 3 " + NOT_MATCHED,
                new MapSqlParameterSource("amt", l.amount()).addValue("d", l.date()),
                (rs, i) -> new Candidate(rs.getLong("entry_id"), similarity(l.description(), rs.getString("description"))));
        return rows.stream()
                .filter(c -> c.score() >= fuzzyThreshold)
                .max(Comparator.comparingDouble(Candidate::score));
    }

    private Optional<Candidate> semantic(Line l) {
        if (l.embedding() == null) {
            return Optional.empty();
        }
        List<Candidate> rows = jdbc.query(
                "select b.entry_id, 1 - (e.embedding <=> cast(:vec as vector)) as sim "
                        + "from bank_side_entries b join journal_entries e on e.id = b.entry_id "
                        + "where e.embedding is not null "
                        + "and abs(b.bank_amount - :amt) <= abs(:amt) * 0.01 + 0.005 "
                        + "and abs(b.entry_date - cast(:d as date)) <= 5 " + NOT_MATCHED
                        + "order by e.embedding <=> cast(:vec as vector) limit 1",
                new MapSqlParameterSource("vec", l.embedding()).addValue("amt", l.amount()).addValue("d", l.date()),
                (rs, i) -> new Candidate(rs.getLong("entry_id"), rs.getDouble("sim")));
        return rows.stream().filter(c -> c.score() >= semanticThreshold).findFirst();
    }

    // ---- helpers ----

    private double similarity(String a, String b) {
        Double score = jw.apply(norm(a), norm(b));
        return score == null ? 0 : score;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    private void saveMatch(long lineId, long entryId, String method, double confidence) {
        jdbc.update("insert into matches (statement_line_id, entry_id, method, confidence) values (:l, :e, :m, :c)",
                new MapSqlParameterSource("l", lineId).addValue("e", entryId).addValue("m", method)
                        .addValue("c", BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP)));
        jdbc.update("update bank_statement_lines set status = 'MATCHED' where id = :l", Map.of("l", lineId));
    }

    private List<Line> unmatchedLines(long batchId) {
        return jdbc.query("select id, txn_date, amount, description, reference, cast(embedding as text) as emb "
                        + "from bank_statement_lines where batch_id = :b and status = 'UNMATCHED' order by id",
                Map.of("b", batchId),
                (rs, i) -> new Line(rs.getLong("id"), rs.getObject("txn_date", LocalDate.class), rs.getBigDecimal("amount"),
                        rs.getString("description"), rs.getString("reference"), rs.getString("emb")));
    }

    /** ledger entries get their vector the first time a reconciliation needs it */
    private void embedMissingEntries() {
        List<Long> ids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        jdbc.query("select e.id, coalesce(e.description, '') as description from journal_entries e "
                        + "join bank_side_entries b on b.entry_id = e.id where e.embedding is null",
                Map.of(),
                (RowCallbackHandler) rs -> {
                    ids.add(rs.getLong("id"));
                    texts.add(rs.getString("description"));
                });
        if (ids.isEmpty()) {
            return;
        }
        List<float[]> vectors = embedder.embedAll(texts);
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("update journal_entries set embedding = cast(:v as vector) where id = :id",
                    new MapSqlParameterSource("v", VectorUtil.literal(vectors.get(i))).addValue("id", ids.get(i)));
        }
    }

    private Summary summary(long batchId, int anomalyCount) {
        Map<String, Integer> byMethod = new HashMap<>();
        jdbc.query("select m.method, count(*) as n from matches m join bank_statement_lines l on l.id = m.statement_line_id "
                        + "where l.batch_id = :b group by m.method",
                Map.of("b", batchId),
                (RowCallbackHandler) rs -> {
                    byMethod.put(rs.getString("method"), rs.getInt("n"));
                });
        Integer total = jdbc.queryForObject("select count(*) from bank_statement_lines where batch_id = :b",
                Map.of("b", batchId), Integer.class);
        Integer unmatched = jdbc.queryForObject("select count(*) from bank_statement_lines where batch_id = :b and status = 'UNMATCHED'",
                Map.of("b", batchId), Integer.class);
        int exact = byMethod.getOrDefault("EXACT", 0);
        int fuzzy = byMethod.getOrDefault("FUZZY", 0);
        int semantic = byMethod.getOrDefault("SEMANTIC", 0);
        int manual = byMethod.getOrDefault("MANUAL", 0);
        int t = total == null ? 0 : total;
        double rate = t == 0 ? 0 : (double) (exact + fuzzy + semantic + manual) / t;
        return new Summary(batchId, t, exact, fuzzy, semantic, manual, unmatched == null ? 0 : unmatched,
                Math.round(rate * 1000.0) / 1000.0, anomalyCount);
    }
}
