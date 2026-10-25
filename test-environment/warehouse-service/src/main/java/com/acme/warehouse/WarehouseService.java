package com.acme.warehouse;

import com.acme.libs.reactive.ExchangeClients;
import com.acme.libs.reactive.ShippingExchange;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class WarehouseService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final ReactiveStringRedisTemplate redis;
    private final WebClient inventory;
    private final ShippingExchange shipping;

    public WarehouseService(ReactiveStringRedisTemplate redis, WarehouseClientsProperties props) {
        this.redis = redis;
        this.inventory = WebClient.builder().baseUrl(props.inventoryUrl()).build();
        this.shipping = ExchangeClients.create(props.shippingUrl(), ShippingExchange.class);
    }

    @Cacheable(cacheNames = "hot-stock")
    public Mono<String> hotStock() {
        return redis.opsForSet().members("hot-skus").collectList().map(String::valueOf).cache();
    }

    public Mono<Map<String, Object>> dispatch(String sku) {
        return inventory.put().uri("/inventory/{sku}/reserve", sku).retrieve().toBodilessEntity()
                .then(shipping.createShipment(Map.of("sku", sku)))
                .flatMap(s -> redis.opsForHash().put("dispatch:" + sku, "shipment", String.valueOf(s.get("id"))).thenReturn(s))
                .onErrorResume(e -> inventory.delete().uri("/inventory/{sku}/reservation", sku).retrieve().bodyToMono(MAP)
                        .then(redis.delete("dispatch:" + sku))
                        .thenReturn(Map.of("error", e.getMessage())));
    }
}
