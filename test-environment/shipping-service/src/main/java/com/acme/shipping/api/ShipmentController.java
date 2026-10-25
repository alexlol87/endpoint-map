package com.acme.shipping.api;

import com.acme.shipping.carrier.CarrierGateway;
import com.acme.shipping.carrier.LabelPrinter;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShipmentController implements ShipmentApi {

    private final CarrierGateway defaultCarrier;   // @Primary -> DhlCarrier
    private final CarrierGateway expressCarrier;   // @Qualifier("ups") -> UpsCarrier
    private final LabelPrinter labelPrinter;       // two conditional impls, no qualifier -> oneOf
    private final BillingExchangeClient billing;

    public ShipmentController(CarrierGateway defaultCarrier,
                              @Qualifier("ups") CarrierGateway expressCarrier,
                              LabelPrinter labelPrinter,
                              BillingExchangeClient billing) {
        this.defaultCarrier = defaultCarrier;
        this.expressCarrier = expressCarrier;
        this.labelPrinter = labelPrinter;
        this.billing = billing;
    }

    @Override
    public Map<String, Object> create(Map<String, Object> request) {
        String tracking = defaultCarrier.book(String.valueOf(request.get("orderId")));
        labelPrinter.print(tracking);
        billing.createInvoice(Map.of("orderId", request.get("orderId"), "type", "shipping"));
        return Map.of("tracking", tracking);
    }

    @Override
    public Map<String, Object> createExpress(Map<String, Object> request) {
        String tracking = expressCarrier.book(String.valueOf(request.get("orderId")));
        labelPrinter.print(tracking);
        return Map.of("tracking", tracking, "express", true);
    }

    @Override
    public Map<String, Object> get(String id) {
        return Map.of("id", id, "status", defaultCarrier.track(id));
    }

    @Override
    public void cancel(String id) {
        defaultCarrier.cancel(id);
    }
}
