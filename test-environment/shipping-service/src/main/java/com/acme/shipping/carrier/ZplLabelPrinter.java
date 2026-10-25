package com.acme.shipping.carrier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Second implementation without HTTP calls (prints locally). */
@Component
@ConditionalOnProperty(name = "labels.format", havingValue = "zpl")
public class ZplLabelPrinter implements LabelPrinter {

    @Override
    public void print(String trackingId) {
        System.out.println("^XA^FO50,50^FD" + trackingId + "^FS^XZ");
    }
}
