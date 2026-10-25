package com.example.messaging.batch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Line reader that never buffers more than {@code maxLine} characters of one line (a hostile file without line
 * breaks cannot exhaust the heap). An over-long line is consumed up to its terminator, so parsing resynchronises on
 * the next line, and is returned as {@link #TOO_LONG}. Terminators are {@code \n}, {@code \r}, {@code \r\n}.
 */
public class BoundedLineReader extends BufferedReader {

    /** Sentinel returned instead of an over-long line. Contains a NUL, so it cannot be real CSV text. */
    public static final String TOO_LONG = "\u0000LINE_TOO_LONG";

    private final int maxLine;
    private boolean pendingLf;

    public BoundedLineReader(Reader in, int maxLine) {
        super(in, 8192);
        this.maxLine = maxLine;
    }

    @Override
    public String readLine() throws IOException {
        StringBuilder sb = null;
        boolean tooLong = false;
        boolean any = false;
        while (true) {
            int c = read();
            if (c < 0) {
                if (!any) {
                    return null;
                }
                break;
            }
            if (pendingLf) {
                pendingLf = false;
                if (c == '\n') {
                    continue; // second half of a CRLF terminator
                }
            }
            any = true;
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                pendingLf = true;
                break;
            }
            if (tooLong) {
                continue;
            }
            if (sb != null && sb.length() >= maxLine) {
                tooLong = true;
                sb = null;
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(64);
            }
            sb.append((char) c);
        }
        if (tooLong) {
            return TOO_LONG;
        }
        return sb == null ? "" : sb.toString();
    }
}
