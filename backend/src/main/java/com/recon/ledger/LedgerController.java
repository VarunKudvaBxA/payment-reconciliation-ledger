package com.recon.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api")
public class LedgerController {

    public record PaymentRequest(String from, String to,
                                 @NotNull @DecimalMin("0.01") BigDecimal amount,
                                 String description) {
    }

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/accounts")
    public List<Map<String, Object>> accounts() {
        return ledger.accounts();
    }

    @GetMapping("/entries")
    public List<Map<String, Object>> entries(@RequestParam(defaultValue = "30") int limit) {
        return ledger.entries(limit);
    }

    @PostMapping("/deposits")
    public ResponseEntity<Map<String, Object>> deposit(@Valid @RequestBody PaymentRequest r,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return run(key, () -> ledger.deposit(r.to(), r.amount(), text(r, "Deposit"), null, key));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<Map<String, Object>> withdraw(@Valid @RequestBody PaymentRequest r,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return run(key, () -> ledger.withdraw(r.from(), r.amount(), text(r, "Withdrawal"), null, key));
    }

    @PostMapping("/transfers")
    public ResponseEntity<Map<String, Object>> transfer(@Valid @RequestBody PaymentRequest r,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return run(key, () -> ledger.transfer(r.from(), r.to(), r.amount(), text(r, "Transfer"), null, key));
    }

    private ResponseEntity<Map<String, Object>> run(String key, Supplier<LedgerService.Posted> action) {
        try {
            return respond(action.get());
        } catch (DuplicateKeyException e) {
            // two copies of the same request raced, the loser lands here and gets the winner's entry
            Long id = (key == null) ? null : ledger.findByKey(key);
            if (id == null) throw e;
            return respond(new LedgerService.Posted(id, true));
        }
    }

    private ResponseEntity<Map<String, Object>> respond(LedgerService.Posted p) {
        return ResponseEntity.status(p.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(Map.of("entryId", p.entryId(), "replayed", p.replayed()));
    }

    private String text(PaymentRequest r, String fallback) {
        return (r.description() == null || r.description().isBlank()) ? fallback : r.description();
    }
}
