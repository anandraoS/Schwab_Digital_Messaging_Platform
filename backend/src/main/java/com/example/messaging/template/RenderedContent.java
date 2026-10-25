package com.example.messaging.template;

/**
 * Output of the renderer. {@code subject} is the email subject or push title (null for SMS). {@code sms} is only set
 * for the SMS channel.
 */
public record RenderedContent(String subject, String body, boolean html, SmsInfo sms) {

    /** Hides content from accidental logging. */
    @Override
    public String toString() {
        return "RenderedContent[html=" + html + ", bodyLength=" + (body == null ? 0 : body.length()) + ", sms=" + sms + "]";
    }

    /** @param encoding GSM-7 or UCS-2 */
    public record SmsInfo(String encoding, int characters, int segments) {}
}
