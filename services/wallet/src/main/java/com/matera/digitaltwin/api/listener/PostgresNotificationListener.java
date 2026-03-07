package com.matera.digitaltwin.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matera.digitaltwin.api.service.UserAccountProvisioningService;
import jakarta.annotation.PostConstruct;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

/**
 * Listens on the PostgreSQL 'user_created' channel.
 * When a new user is inserted into digitaltwinapp.users, the trigger fires pg_notify
 * and this listener calls UserAccountProvisioningService to create mini-core accounts.
 *
 * Uses a dedicated long-lived connection (not from HikariCP pool) since LISTEN
 * requires a persistent connection that isn't returned to the pool between calls.
 */
@Component
public class PostgresNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PostgresNotificationListener.class);
    private static final String CHANNEL = "user_created";
    private static final int POLL_TIMEOUT_MS = 5_000;

    private final DataSource dataSource;
    private final UserAccountProvisioningService provisioningService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresNotificationListener(DataSource dataSource,
                                        UserAccountProvisioningService provisioningService) {
        this.dataSource = dataSource;
        this.provisioningService = provisioningService;
    }

    @PostConstruct
    public void start() {
        Thread thread = new Thread(this::listenLoop, "pg-notify-listener");
        thread.setDaemon(true);
        thread.start();
        log.info("PostgreSQL notification listener started on channel '{}'", CHANNEL);
    }

    @SuppressWarnings("java:S2189") // intentional infinite loop — daemon thread
    private void listenLoop() {
        while (true) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                stmt.execute("LISTEN " + CHANNEL);
                PGConnection pgConn = conn.unwrap(PGConnection.class);
                log.debug("LISTEN {} registered", CHANNEL);

                while (true) {
                    PGNotification[] notifications = pgConn.getNotifications(POLL_TIMEOUT_MS);
                    if (notifications != null) {
                        for (PGNotification notification : notifications) {
                            handleNotification(notification.getParameter());
                        }
                    }
                }

            } catch (Exception e) {
                log.warn("pg-notify-listener lost connection, reconnecting in 5s: {}", e.getMessage());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handleNotification(String payload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            long userId = ((Number) data.get("user_id")).longValue();
            log.info("Received user_created notification for user_id={}", userId);
            provisioningService.provisionUser(userId);
        } catch (Exception e) {
            log.error("Failed to handle user_created notification payload='{}': {}", payload, e.getMessage());
        }
    }
}
