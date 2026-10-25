package com.acme.inventory;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Class-level prefix given as an array (two prefixes) -> every method doubles. */
@RestController
@RequestMapping({"/inventory", "/v2/inventory"})
public class InventoryController {

    private static final String SKU_PATH = "/{sku}";

    private final StockService stock;

    public InventoryController(StockService stock) {
        this.stock = stock;
    }

    /** Path taken from a constant. */
    @GetMapping(SKU_PATH)
    public Map<String, Object> get(@PathVariable String sku) {
        return stock.get(sku);
    }

    @PutMapping(path = SKU_PATH + "/reserve")
    public void reserve(@PathVariable String sku) {
        stock.reserve(sku);
    }

    @DeleteMapping("/{sku}/reservation")
    public void release(@PathVariable String sku) {
        stock.release(sku);
    }

    @PatchMapping("/{sku}/quantity")
    public void adjust(@PathVariable String sku) {
        stock.adjust(sku);
    }

    /** Regex path variable. */
    @GetMapping("/warehouse/{code:[A-Z]{3}}/items")
    public List<StockItem> byWarehouse(@PathVariable String code) {
        return stock.byWarehouse(code);
    }
}
