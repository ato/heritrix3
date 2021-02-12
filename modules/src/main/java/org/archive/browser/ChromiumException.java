package org.archive.browser;

public class ChromiumException extends RuntimeException {
    public ChromiumException(String message) {
        super(message);
    }

    public ChromiumException(String message, Throwable cause) {
        super(message, cause);
    }
}
