package com.recon.recon;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Plain SQL and simple statistics, no model needed for these four checks:
 *   DUPLICATE           same date, amount and description appears more than once
 *   AMOUNT_OUTLIER      amount more than 3 standard deviations from the batch mean
 *   AMOUNT_VARIANCE     matched pair where bank and ledger amounts differ (fees, rounding)
 *   MISSING_SETTLEMENT  ledger entry with no bank line after a few days
 */
@Service
public class AnomalyService {
    private final NamedParameterJdbcTemplate jdbc;
    private final int missingDays;

    public AnomalyService(NamedParameterJdbcTemplate jdbc, @Value("${recon.missing-settlement-days}") int missingDays) {
        this.jdbc = jdbc;
        this.missingDays = missingDays;
    }

    @Transactional
    public int scan(long batchId) {
        MapSqlParameterSource p = new MapSqlParameterSource("b", batchId).addValue("days", missingDays);

        jdbc.update("delete from anomalies where statement_line_id in (select id from bank_statement_lines where batch_id = :b)", p);
        jdbc.update("delete from anomalies where kind = 'MISSING_SETTLEMENT'", p);

        int found = 0;

        found += jdbc.update("""
                insert into anomalies (statement_line_id, kind, detail)
                select id, 'DUPLICATE', 'Same date, amount and description as line ' || first_id
                  from (select id,
                               min(id) over w as first_id,
                               count(*) over w as copies
                          from bank_statement_lines
                         where batch_id = :b
                        window w as (partition by txn_date, amount, lower(description))) t
                 where copies > 1 and id <> first_id
                """, p);

        found += jdbc.update("""
                insert into anomalies (statement_line_id, kind, detail)
                select id, 'AMOUNT_OUTLIER',
                       'Amount ' || amount || ' is ' || round(cast(z as numeric), 1) || ' standard deviations above the batch average'
                  from (select id, amount,
                               (abs(amount) - avg(abs(amount)) over ()) / nullif(stddev_pop(abs(amount)) over (), 0) as z
                          from bank_statement_lines
                         where batch_id = :b) t
                 where z > 3
                """, p);

        found += jdbc.update("""
                insert into anomalies (statement_line_id, entry_id, kind, detail)
                select l.id, m.entry_id, 'AMOUNT_VARIANCE',
                       'Bank shows ' || l.amount || ' but the ledger has ' || b.bank_amount
                  from matches m
                  join bank_statement_lines l on l.id = m.statement_line_id
                  join bank_side_entries b on b.entry_id = m.entry_id
                 where l.batch_id = :b and abs(l.amount - b.bank_amount) > 0.005
                """, p);

        found += jdbc.update("""
                insert into anomalies (entry_id, kind, detail)
                select b.entry_id, 'MISSING_SETTLEMENT',
                       'No bank line found ' || (current_date - b.entry_date) || ' days after posting'
                  from bank_side_entries b
                 where not exists (select 1 from matches m where m.entry_id = b.entry_id)
                   and current_date - b.entry_date > :days
                """, p);

        return found;
    }

    public Map<String, Object> countsByKind() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        jdbc.query("select kind, count(*) as n from anomalies group by kind order by kind",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    out.put(rs.getString("kind"), rs.getInt("n"));
                });
        return out;
    }
}
