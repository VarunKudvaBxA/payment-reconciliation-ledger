package com.recon.demo;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoController {
    private final DemoDataService demo;

    public DemoController(DemoDataService demo) {
        this.demo = demo;
    }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "150") int count) {
        return demo.seed(count);
    }

    @GetMapping(value = "/statement.csv", produces = "text/csv")
    public ResponseEntity<String> statement() throws IOException {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=demo-statement.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(demo.statementCsv());
    }
}
