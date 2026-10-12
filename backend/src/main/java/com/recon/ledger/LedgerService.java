package com.recon.ledger;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Double entry ledger on plain JDBC.
 *
 * How concurrent writes stay safe: every account touched by an entry is locked with
 * select ... for update, always in ascending id order, so two transfers can never wait on each
 * other in a circle. Balances are checked after the lock is held, so two transfers cannot both
 * spend the same money. The idempotency key has a unique index, so a retried request cannot
 * post twice even if two copies arrive at the same moment.
 */
@Service
public class LedgerService {

    public record Line(String account, char direction, BigDecimal amount) {
    }

    public record Posted(long entryId, boolean replayed) {
    }

    private record Account(long id, String code, String type, boolean allowNegative) {
    }

    public static final String BANK = "CASH_BANK";

    private final NamedParameterJdbcTemplate jdbc;

    public LedgerService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- convenience operations ----

    public Posted deposit(String wallet, BigDecimal amount, String description, String reference, String key) {
        return post(reference, description, key, List.of(
                new Line(BANK, 'D', amount),
                new Line(wallet, 'C', amount)));
    }

    public Posted withdraw(String wallet, BigDecimal amount, String description, String reference, String key) {
        return post(reference, description, key, List.of(
                new Line(wallet, 'D', amount),
                new Line(BANK, 'C', amount)));
    }

    public Posted transfer(String from, String to, BigDecimal amount, String description, String reference, String key) {
        if (from == null || to == null || from.equals(to)) {
            throw new IllegalArgumentException("Pick two different accounts");
        }
        return post(reference, description, key, List.of(
                new Line(from, 'D', amount),
                new Line(to, 'C', amount)));
    }

    // ---- core ----

    @Transactional
    public Posted post(String reference, String description, String idempotencyKey, List<Line> rawLines) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Long existing = findByKey(idempotencyKey);
            if (existing != null) {
                return new Posted(existing, true);
            }
        }

        List<Line> lines = normalise(rawLines);

        Map<String, Account> byCode = loadAccounts(lines);
        Map<Long, Account> byId = new HashMap<>();
        byCode.values().forEach(a -> byId.put(a.id(), a));

        // lock in id order, then read the balances we just locked
        List<Long> ids = byId.keySet().stream().sorted().toList();
        Map<Long, BigDecimal> balances = new HashMap<>();
        jdbc.query("select account_id, net_debit from account_balances where account_id in (:ids) order by account_id for update",
                new MapSqlParameterSource("ids", ids),
                (RowCallbackHandler) rs -> {
                    balances.put(rs.getLong(1), rs.getBigDecimal(2));
                });

        Map<Long, BigDecimal> delta = new HashMap<>();
        for (Line l : lines) {
            long id = byCode.get(l.account()).id();
            BigDecimal signed = l.direction() == 'D' ? l.amount() : l.amount().negate();
            delta.merge(id, signed, BigDecimal::add);
        }
        for (Map.Entry<Long, BigDecimal> e : delta.entrySet()) {
            Account acc = byId.get(e.getKey());
            BigDecimal after = balances.get(acc.id()).add(e.getValue());
            boolean debitNormal = acc.type().equals("ASSET") || acc.type().equals("EXPENSE");
            boolean overdrawn = debitNormal ? after.signum() < 0 : after.signum() > 0;
            if (overdrawn && !acc.allowNegative()) {
                throw new InsufficientFundsException(acc.code());
            }
        }

        String ref = (reference == null || reference.isBlank()) ? "TX-" + UUID.randomUUID().toString().substring(0, 8) : reference;
        Long entryId = jdbc.queryForObject(
                "insert into journal_entries (reference, description, idempotency_key) values (:ref, :descr, :key) returning id",
                new MapSqlParameterSource()
                        .addValue("ref", ref)
                        .addValue("descr", description)
                        .addValue("key", (idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey),
                Long.class);

        SqlParameterSource[] lineParams = lines.stream()
                .map(l -> new MapSqlParameterSource()
                        .addValue("entry", entryId)
                        .addValue("account", byCode.get(l.account()).id())
                        .addValue("dir", String.valueOf(l.direction()))
                        .addValue("amount", l.amount()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("insert into ledger_lines (entry_id, account_id, direction, amount) values (:entry, :account, :dir, :amount)", lineParams);

        for (Map.Entry<Long, BigDecimal> e : delta.entrySet()) {
            jdbc.update("update account_balances set net_debit = net_debit + :d where account_id = :id",
                    new MapSqlParameterSource("d", e.getValue()).addValue("id", e.getKey()));
        }
        return new Posted(entryId, false);
    }

    public Long findByKey(String key) {
        List<Long> ids = jdbc.queryForList("select id from journal_entries where idempotency_key = :k",
                Map.of("k", key), Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ---- reads ----

    public List<Map<String, Object>> accounts() {
        return jdbc.queryForList("""
                select a.id, a.code, a.name, a.type,
                       case when a.type in ('ASSET', 'EXPENSE') then b.net_debit else -b.net_debit end as balance
                  from accounts a join account_balances b on b.account_id = a.id
                 order by a.id
                """, Map.of());
    }

    public List<Map<String, Object>> entries(int limit) {
        List<Map<String, Object>> entries = jdbc.queryForList(
                "select id, reference, description, posted_at from journal_entries order by id desc limit :n",
                Map.of("n", Math.min(Math.max(limit, 1), 200)));
        if (entries.isEmpty()) {
            return entries;
        }
        List<Long> ids = entries.stream().map(e -> ((Number) e.get("id")).longValue()).toList();
        Map<Long, List<Map<String, Object>>> linesByEntry = new HashMap<>();
        jdbc.query("""
                        select l.entry_id, a.code, l.direction, l.amount
                          from ledger_lines l join accounts a on a.id = l.account_id
                         where l.entry_id in (:ids) order by l.id
                        """,
                new MapSqlParameterSource("ids", ids),
                (RowCallbackHandler) rs -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("account", rs.getString("code"));
                    line.put("direction", rs.getString("direction"));
                    line.put("amount", rs.getBigDecimal("amount"));
                    linesByEntry.computeIfAbsent(rs.getLong("entry_id"), k -> new ArrayList<>()).add(line);
                });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            Map<String, Object> copy = new LinkedHashMap<>(e);
            copy.put("lines", linesByEntry.getOrDefault(((Number) e.get("id")).longValue(), List.of()));
            out.add(copy);
        }
        return out;
    }

    // ---- helpers ----

    private List<Line> normalise(List<Line> lines) {
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("An entry needs at least two lines");
        }
        List<Line> out = new ArrayList<>();
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.amount() == null || l.amount().signum() <= 0) {
                throw new IllegalArgumentException("Amounts must be greater than zero");
            }
            BigDecimal amount;
            try {
                amount = l.amount().setScale(2, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Amounts can have at most 2 decimal places");
            }
            if (l.direction() != 'D' && l.direction() != 'C') {
                throw new IllegalArgumentException("Direction must be D or C");
            }
            if (l.direction() == 'D') debits = debits.add(amount);
            else credits = credits.add(amount);
            out.add(new Line(l.account(), l.direction(), amount));
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Debits and credits must be equal");
        }
        return out;
    }

    private Map<String, Account> loadAccounts(List<Line> lines) {
        List<String> codes = lines.stream().map(Line::account).distinct().toList();
        Map<String, Account> found = new HashMap<>();
        jdbc.query("select id, code, type, allow_negative from accounts where code in (:codes)",
                new MapSqlParameterSource("codes", codes),
                (RowCallbackHandler) rs -> {
                    found.put(rs.getString("code"),
                            new Account(rs.getLong("id"), rs.getString("code"), rs.getString("type"), rs.getBoolean("allow_negative")));
                });
        if (found.size() != codes.size()) {
            throw new IllegalArgumentException("Unknown account in entry");
        }
        return found;
    }
}
