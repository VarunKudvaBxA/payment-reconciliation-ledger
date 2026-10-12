package com.recon.recon;

import com.recon.embedding.Embedder;
import com.recon.embedding.VectorUtil;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class StatementService {

    private record Row(LocalDate date, BigDecimal amount, String description, String reference) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Embedder embedder;

    public StatementService(NamedParameterJdbcTemplate jdbc, Embedder embedder) {
        this.jdbc = jdbc;
        this.embedder = embedder;
    }

    /** csv columns: date (yyyy-mm-dd), amount, description, reference (optional) */
    @Transactional
    public Map<String, Object> ingest(String filename, InputStream in) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

        List<Row> rows = new ArrayList<>();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {

            Set<String> headers = new java.util.HashSet<>();
            parser.getHeaderMap().keySet().forEach(h -> headers.add(h.toLowerCase(Locale.ROOT)));
            for (String needed : List.of("date", "amount", "description")) {
                if (!headers.contains(needed)) {
                    throw new IllegalArgumentException("The csv needs a '" + needed + "' column. Expected: date, amount, description, reference");
                }
            }
            for (CSVRecord rec : parser) {
                try {
                    String ref = rec.isMapped("reference") ? rec.get("reference") : null;
                    rows.add(new Row(
                            LocalDate.parse(rec.get("date")),
                            new BigDecimal(rec.get("amount").replace(",", "")),
                            rec.get("description"),
                            (ref == null || ref.isBlank()) ? null : ref));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Row " + rec.getRecordNumber() + " could not be read. Dates must look like 2026-03-31 and amounts must be numbers.");
                }
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file has no rows");
        }

        Long batchId = jdbc.queryForObject("insert into statement_batches (filename) values (:f) returning id",
                new MapSqlParameterSource("f", filename), Long.class);

        List<float[]> vectors = embedder.embedAll(rows.stream().map(Row::description).toList());
        SqlParameterSource[] params = new SqlParameterSource[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            params[i] = new MapSqlParameterSource()
                    .addValue("batch", batchId)
                    .addValue("date", r.date())
                    .addValue("amount", r.amount())
                    .addValue("descr", r.description())
                    .addValue("ref", r.reference())
                    .addValue("emb", VectorUtil.literal(vectors.get(i)));
        }
        jdbc.batchUpdate("""
                insert into bank_statement_lines (batch_id, txn_date, amount, description, reference, embedding)
                values (:batch, :date, :amount, :descr, :ref, cast(:emb as vector))
                """, params);

        return Map.of("batchId", batchId, "lines", rows.size());
    }
}
