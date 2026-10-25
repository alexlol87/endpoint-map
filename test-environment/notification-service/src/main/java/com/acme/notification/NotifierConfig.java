package com.acme.notification;

import com.acme.libs.reactive.EmailNotifier;
import com.acme.libs.reactive.Notifier;
import com.acme.libs.reactive.SmsNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotifierConfig {

    @Bean("email")
    Notifier emailNotifier() {
        return new EmailNotifier();
    }

    @Bean(name = "sms")
    Notifier smsNotifier(@Value("${notification.self-url:http://localhost:8088}") String selfUrl) {
        return new SmsNotifier(selfUrl);
    }
}
