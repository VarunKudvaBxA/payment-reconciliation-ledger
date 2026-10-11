package com.recon;

import com.recon.ledger.InsufficientFundsException;
import com.recon.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class LedgerConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String[] WALLETS = {"WALLET_ALICE", "WALLET_BOB", "WALLET_CAROL", "WALLET_DAVE"};

    @Autowired
    LedgerService ledger;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void sameIdempotencyKeyPostsOnlyOnce() {
        var first = ledger.deposit("WALLET_ALICE", new BigDecimal("10.00"), "one", null, "idem-test-1");
        var second = ledger.deposit("WALLET_ALICE", new BigDecimal("10.00"), "one again", null, "idem-test-1");

        assertEquals(first.entryId(), second.entryId());
        assertTrue(second.replayed());
        Integer rows = jdbc.queryForObject("select count(*) from journal_entries where idempotency_key = 'idem-test-1'",
                Map.of(), Integer.class);
        assertEquals(1, rows);
    }

    @Test
    void thousandParallelTransfersKeepTheBooksBalanced() throws Exception {
        for (String w : WALLETS) {
            ledger.deposit(w, new BigDecimal("1000.00"), "seed", null, "seed-" + w);
        }
        BigDecimal before = walletTotal();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        AtomicInteger done = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                Random rnd = new Random(n);
                String from = WALLETS[rnd.nextInt(WALLETS.length)];
                String to = WALLETS[(java.util.Arrays.asList(WALLETS).indexOf(from) + 1 + rnd.nextInt(WALLETS.length - 1)) % WALLETS.length];
                BigDecimal amount = BigDecimal.valueOf(100 + rnd.nextInt(9000), 2);
                try {
                    ledger.transfer(from, to, amount, "parallel", null, "par-" + n);
                    done.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    rejected.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(); // rethrows anything unexpected, like a deadlock
        }
        pool.shutdown();

        assertEquals(1000, done.get() + rejected.get());
        assertEquals(0, before.compareTo(walletTotal()), "money was created or destroyed");

        BigDecimal netOfAllLines = jdbc.queryForObject(
                "select coalesce(sum(case direction when 'D' then amount else -amount end), 0) from ledger_lines",
                Map.of(), BigDecimal.class);
        assertEquals(0, BigDecimal.ZERO.compareTo(netOfAllLines), "debits and credits do not match");

        Integer negative = jdbc.queryForObject(
                "select count(*) from account_balances b join accounts a on a.id = b.account_id "
                        + "where a.type = 'LIABILITY' and b.net_debit > 0", Map.of(), Integer.class);
        assertEquals(0, negative, "a wallet went below zero");

        // the cached balance must agree with the lines it was built from
        Integer drift = jdbc.queryForObject("""
                select count(*) from account_balances b
                 where b.net_debit <> coalesce((select sum(case l.direction when 'D' then l.amount else -l.amount end)
                                                  from ledger_lines l where l.account_id = b.account_id), 0)
                """, Map.of(), Integer.class);
        assertEquals(0, drift, "cached balance drifted from the ledger lines");
    }

    private BigDecimal walletTotal() {
        return jdbc.queryForObject(
                "select coalesce(sum(-b.net_debit), 0) from account_balances b join accounts a on a.id = b.account_id "
                        + "where a.type = 'LIABILITY'", Map.of(), BigDecimal.class);
    }
}
