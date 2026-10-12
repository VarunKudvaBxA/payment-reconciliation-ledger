package com.recon.demo;

import com.recon.ledger.InsufficientFundsException;
import com.recon.ledger.LedgerService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Makes fake ledger activity and a matching bank statement that is deliberately messy, so the
 * reconciliation has something to chew on. Roughly: most lines match exactly, some have
 * noisy descriptions, some arrive days late with a fee taken off, some never arrive, and a few
 * lines exist only at the bank.
 */
@Service
public class DemoDataService {
    private static final String[] WALLETS = {"WALLET_ALICE", "WALLET_BOB", "WALLET_CAROL", "WALLET_DAVE"};
    private static final String[] IN_TEXT = {"Card payment from", "Wallet top up by", "Bank transfer from", "Customer payment from"};
    private static final String[] OUT_TEXT = {"Payout to", "Withdrawal to bank for", "Settlement payout to"};

    private final LedgerService ledger;
    private final NamedParameterJdbcTemplate jdbc;

    public DemoDataService(LedgerService ledger, NamedParameterJdbcTemplate jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    public Map<String, Object> seed(int count) {
        int n = Math.min(Math.max(count, 1), 1000);
        openingBalances();

        Integer existing = jdbc.queryForObject("select count(*) from journal_entries where reference ~ '^DEMO-[0-9]+$'",
                Map.of(), Integer.class);
        int start = existing == null ? 0 : existing;
        Random rnd = new Random(42L + start);

        int created = 0;
        for (int i = 1; i <= n; i++) {
            String ref = String.format("DEMO-%05d", start + i);
            String wallet = WALLETS[rnd.nextInt(WALLETS.length)];
            String name = wallet.substring("WALLET_".length());
            BigDecimal amount = BigDecimal.valueOf(500 + rnd.nextInt(89500), 2);
            boolean incoming = rnd.nextDouble() < 0.7;
            try {
                LedgerService.Posted posted = incoming
                        ? ledger.deposit(wallet, amount, IN_TEXT[rnd.nextInt(IN_TEXT.length)] + " " + name, ref, "demo-" + ref)
                        : ledger.withdraw(wallet, amount, OUT_TEXT[rnd.nextInt(OUT_TEXT.length)] + " " + name, ref, "demo-" + ref);
                backdate(posted.entryId(), 4 + rnd.nextInt(20));
                created++;
            } catch (InsufficientFundsException e) {
                // a wallet ran dry, skip this one
            }
        }
        return Map.of("created", created, "ledgerEntriesWithDemoReference", start + created);
    }

    public String statementCsv() throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select entry_id, reference, description, entry_date, bank_amount from bank_side_entries "
                        + "where reference like 'DEMO-%' order by entry_id", Map.of());

        Random rnd = new Random(7);
        List<String[]> exactLines = new ArrayList<>();
        StringWriter out = new StringWriter();
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader("date", "amount", "description", "reference").build();

        try (CSVPrinter csv = new CSVPrinter(out, format)) {
            for (Map<String, Object> row : rows) {
                String ref = (String) row.get("reference");
                String description = (String) row.get("description");
                LocalDate date = ((java.sql.Date) row.get("entry_date")).toLocalDate();
                BigDecimal amount = (BigDecimal) row.get("bank_amount");
                double roll = ref.startsWith("DEMO-OPEN") ? 0.5 : rnd.nextDouble();

                if (roll < 0.06) {
                    continue; // never settled at the bank
                } else if (roll < 0.72) {
                    String[] line = {date.toString(), amount.toPlainString(), description, ref};
                    csv.printRecord((Object[]) line);
                    exactLines.add(line);
                } else if (roll < 0.88) {
                    // reference lost, description shouted and padded, a day or two late
                    csv.printRecord(date.plusDays(1 + rnd.nextInt(2)), amount.toPlainString(),
                            description.toUpperCase() + " " + (1000 + rnd.nextInt(8999)), "");
                } else {
                    // bank style description, four days late, sometimes a fee already taken off
                    BigDecimal shown = rnd.nextBoolean() ? amount : amount.multiply(new BigDecimal("0.995")).setScale(2, java.math.RoundingMode.HALF_UP);
                    String who = description.substring(description.lastIndexOf(' ') + 1).toUpperCase();
                    String kind = amount.signum() >= 0 ? "CARD PMT FROM" : "PAYOUT TO";
                    csv.printRecord(date.plusDays(4), shown.toPlainString(),
                            "TXN " + (10000 + rnd.nextInt(89999)) + " " + who + " " + kind, "");
                }
            }

            LocalDate recent = LocalDate.now().minusDays(2);
            csv.printRecord(recent, "-12.50", "MONTHLY ACCOUNT FEE", "");
            csv.printRecord(recent, "3.21", "INTEREST PAID", "");
            csv.printRecord(recent, "-25.00", "CHARGEBACK FEE", "");
            csv.printRecord(recent, "250000.00", "UNKNOWN WIRE IN", "");
            for (int i = 0; i < Math.min(2, exactLines.size()); i++) {
                csv.printRecord((Object[]) exactLines.get(rnd.nextInt(exactLines.size())));
            }
        }
        return out.toString();
    }

    private void openingBalances() {
        Integer done = jdbc.queryForObject("select count(*) from journal_entries where reference like 'DEMO-OPEN%'",
                Map.of(), Integer.class);
        if (done != null && done > 0) {
            return;
        }
        for (String wallet : WALLETS) {
            String ref = "DEMO-OPEN-" + wallet.substring("WALLET_".length());
            LedgerService.Posted p = ledger.deposit(wallet, new BigDecimal("50000.00"), "Opening balance " + wallet, ref, "demo-" + ref);
            backdate(p.entryId(), 30);
        }
    }

    private void backdate(long entryId, int days) {
        jdbc.update("update journal_entries set posted_at = now() - make_interval(days => :d) where id = :id",
                new MapSqlParameterSource("d", days).addValue("id", entryId));
    }
}
