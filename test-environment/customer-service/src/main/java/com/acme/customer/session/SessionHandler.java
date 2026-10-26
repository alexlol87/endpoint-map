package com.acme.customer.session;

import java.util.Map;
import org.springframework.stereotype.Component;

/** Handler layer between the controller and the session service. */
@Component
public class SessionHandler {

    private final SessionService sessions;

    public SessionHandler(SessionService sessions) {
        this.sessions = sessions;
    }

    public Map<String, String> load(long customerId) {
        return sessions.current(customerId);
    }

    public void end(long customerId, String token) {
        sessions.logout(customerId, token);
    }
}
