package com.recon.recon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.llm.LlmClient;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/** suggests why a bank line did not match and what to do, a person always confirms */
@Service
public class ExceptionAssistant {

    private static final String SYSTEM = """
            You help a payments operations analyst resolve one unmatched bank statement line.
            Use only the data in the JSON you are given. Do not invent transactions, amounts or dates.
            Reply with JSON only, no markdown, in exactly this shape:
            {"cause": "one or two sentences", "action": "one or two sentences", "best_candidate_entry_id": 123 or null}
            Only set best_candidate_entry_id to an id that appears in the candidates list.
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final LlmClient llm;
    private final ObjectMapper mapper;

    public ExceptionAssistant(NamedParameterJdbcTemplate jdbc, LlmClient llm, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> candidates(long lineId) {
        Map<String, Object> line = line(lineId);
        return jdbc.queryForList("""
                select b.entry_id, b.reference, b.description, b.entry_date, b.bank_amount
                  from bank_side_entries b
                 where not exists (select 1 from matches m where m.entry_id = b.entry_id)
                 order by abs(b.bank_amount - :amt), abs(b.entry_date - cast(:d as date))
                 limit 4
                """, Map.of("amt", line.get("amount"), "d", line.get("txn_date")));
    }

    public Map<String, Object> suggest(long lineId) {
        Map<String, Object> line = line(lineId);
        List<Map<String, Object>> candidates = candidates(lineId);
        List<Map<String, Object>> flags = jdbc.queryForList(
                "select kind, detail from anomalies where statement_line_id = :id", Map.of("id", lineId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidates", candidates);

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("statement_line", line);
        facts.put("flags", flags);
        facts.put("candidates", candidates);

        try {
            Optional<String> reply = llm.complete(SYSTEM, mapper.writeValueAsString(facts), 400);
            if (reply.isPresent()) {
                JsonNode json = parseJson(reply.get());
                if (json != null && json.hasNonNull("cause")) {
                    result.put("source", "LLM");
                    result.put("cause", json.get("cause").asText());
                    result.put("action", json.path("action").asText(""));
                    result.put("bestCandidateEntryId", validCandidate(json.path("best_candidate_entry_id"), candidates));
                    return result;
                }
            }
        } catch (Exception ignored) {
            // fall through to the rule based answer
        }
        result.putAll(byRules(line, flags, candidates));
        result.put("source", "RULES");
        return result;
    }

    private Map<String, Object> byRules(Map<String, Object> line, List<Map<String, Object>> flags, List<Map<String, Object>> candidates) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bestCandidateEntryId", null);

        boolean duplicate = flags.stream().anyMatch(f -> "DUPLICATE".equals(f.get("kind")));
        if (duplicate) {
            out.put("cause", "This line repeats another line in the same statement.");
            out.put("action", "Keep the first one, ignore this copy, and ask the bank if it was charged twice.");
            return out;
        }
        BigDecimal amount = (BigDecimal) line.get("amount");
        for (Map<String, Object> c : candidates) {
            BigDecimal other = (BigDecimal) c.get("bank_amount");
            BigDecimal diff = amount.subtract(other).abs();
            if (diff.compareTo(new BigDecimal("0.005")) < 0) {
                out.put("cause", "A ledger entry with the same amount exists, but its date or description is too different to match automatically.");
                out.put("action", "Check ledger entry " + c.get("entry_id") + " and match it by hand if it is the same payment.");
                out.put("bestCandidateEntryId", ((Number) c.get("entry_id")).longValue());
                return out;
            }
        }
        out.put("cause", "No ledger entry looks similar. This is often a bank fee, interest, or a payment that was never recorded.");
        out.put("action", "Confirm with the bank. If it is real, post an adjusting entry and then match it.");
        return out;
    }

    private Long validCandidate(JsonNode node, List<Map<String, Object>> candidates) {
        if (node == null || !node.canConvertToLong()) {
            return null;
        }
        long id = node.asLong();
        for (Map<String, Object> c : candidates) {
            if (((Number) c.get("entry_id")).longValue() == id) {
                return id;
            }
        }
        return null;
    }

    private JsonNode parseJson(String text) throws Exception {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return mapper.readTree(text.substring(start, end + 1));
    }

    private Map<String, Object> line(long id) {
        return jdbc.queryForMap("select id, txn_date, amount, description, reference from bank_statement_lines where id = :id",
                Map.of("id", id));
    }
}
