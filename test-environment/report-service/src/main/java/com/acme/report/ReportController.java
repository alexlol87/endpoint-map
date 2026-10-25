package com.acme.report;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/reports/{type}")
    public List<Map<String, Object>> report(@PathVariable String type, @RequestParam(defaultValue = "acme") String tenant) {
        return service.build(type, tenant);
    }

    /** Not documented in swagger.json -> code-only. */
    @PostMapping("/reports/export")
    public void export(@RequestBody Map<String, String> body) {
        service.export(body.get("tenant"), body.get("target"));
    }
}
