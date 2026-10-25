package com.acme.tax;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
@SpringBootApplication
@EnableCaching
public class TaxApplication {
    public static void main(String[] args) {
        SpringApplication.run(TaxApplication.class, args);
    }
}
