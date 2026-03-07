package com.matera.digitaltwin.api.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Authenticated user stored in the HTTP session.
 * Must be Serializable — Spring Session JDBC persists session attributes via Java serialization.
 */
public record UserInfo(String email, String name, String picture) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
