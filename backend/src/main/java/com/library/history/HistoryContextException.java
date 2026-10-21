package com.library.history;

public final class HistoryContextException extends RuntimeException {
    private final int status;
    public HistoryContextException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
