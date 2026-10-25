package com.acme.shipping.carrier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "labels.format", havingValue = "pdf", matchIfMissing = true)
public class PdfLabelPrinter implements LabelPrinter {

    private final RestClient client = RestClient.create();

    @Override
    public void print(String trackingId) {
        client.post().uri("https://labels.example-print.com/pdf/{id}", trackingId).retrieve().toBodilessEntity();
    }
}
