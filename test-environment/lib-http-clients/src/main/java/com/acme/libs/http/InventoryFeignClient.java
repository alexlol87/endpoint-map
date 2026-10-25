package com.acme.libs.http;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/** Feign client with a property placeholder url and a path prefix. */
@FeignClient(name = "inventory", url = "${inventory.base-url}", path = "/inventory")
public interface InventoryFeignClient {

    @GetMapping("/{sku}")
    Map<String, Object> getStock(@PathVariable("sku") String sku);

    @PutMapping("/{sku}/reserve")
    void reserve(@PathVariable("sku") String sku);

    @DeleteMapping(value = "/{sku}/reservation")
    void release(@PathVariable("sku") String sku);

    /** Old-style mapping with explicit method attribute. */
    @RequestMapping(method = RequestMethod.PATCH, path = "/{sku}/quantity")
    void adjust(@PathVariable("sku") String sku);
}
