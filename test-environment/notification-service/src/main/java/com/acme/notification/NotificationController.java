package com.acme.notification;

import com.acme.libs.reactive.Notifier;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BASE)
public class NotificationController {

    private final Notifier primaryNotifier;      // @Primary PushNotifier
    private final Notifier smsNotifier;          // narrowed by @Qualifier
    private final List<Notifier> allNotifiers;   // every implementation, in a loop
    private final UserProfileClient profiles;

    public NotificationController(Notifier primaryNotifier, @Qualifier("sms") Notifier smsNotifier,
                                  List<Notifier> allNotifiers, UserProfileClient profiles) {
        this.primaryNotifier = primaryNotifier;
        this.smsNotifier = smsNotifier;
        this.allNotifiers = allNotifiers;
        this.profiles = profiles;
    }

    @PostMapping("/{userId}")
    public void notifyUser(@PathVariable String userId, @RequestBody Map<String, String> body) {
        Map<String, Object> profile = profiles.getProfile(userId);
        if (Boolean.TRUE.equals(profile.get("smsOptIn"))) {
            smsNotifier.notify(userId, body.get("text")).block();
        } else {
            primaryNotifier.notify(userId, body.get("text")).block();
        }
    }

    /** Target of SmsNotifier -> service calls itself through the library (self-edge). */
    @PostMapping("/sms")
    public void sms(@RequestBody Map<String, String> body) {
        System.out.println("SMS to " + body.get("userId"));
    }

    @PostMapping("/broadcast")
    public void broadcast(@RequestBody Map<String, String> body) {
        allNotifiers.forEach(n -> n.notify(body.get("userId"), body.get("text")).block());
    }
}
