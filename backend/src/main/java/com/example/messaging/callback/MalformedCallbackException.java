package com.example.messaging.callback;

/** The callback was authentic but its payload cannot be understood. The message is safe to log (no payload text). */
public class MalformedCallbackException extends RuntimeException {

    public MalformedCallbackException(String reason) {
        super(reason, null, false, false);
    }
}
