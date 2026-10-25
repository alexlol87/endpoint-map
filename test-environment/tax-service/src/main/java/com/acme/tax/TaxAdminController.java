package com.acme.tax;

import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/** Plain @Controller (not @RestController) with @ResponseBody, prefix from another class's constant. */
@Controller
@RequestMapping(TaxPaths.ADMIN)
public class TaxAdminController {

    private final TaxService service;

    public TaxAdminController(TaxService service) {
        this.service = service;
    }

    /** Single verb given as method = RequestMethod.POST (not an array). */
    @RequestMapping(path = "/reload", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> reload() {
        return Map.of("rates", service.allRates().size());
    }

    @GetMapping(TaxPaths.STATUS)
    @ResponseBody
    public Map<String, Object> status() {
        return Map.of("ok", true);
    }

    /** Public but unmapped: not an endpoint. */
    public String version() {
        return "1";
    }
}
