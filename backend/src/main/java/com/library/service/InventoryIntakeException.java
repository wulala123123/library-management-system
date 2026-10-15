package com.library.service;

public class InventoryIntakeException extends RuntimeException {
    private final int status;
    private final String reasonCode;
    private final Long intakeId;
    public InventoryIntakeException(int status, String code, String message) { this(status, code, message, null); }
    public InventoryIntakeException(int status, String code, String message, Long intakeId) {
        super(message); this.status = status; this.reasonCode = code; this.intakeId = intakeId;
    }
    public int status() { return status; }
    public String reasonCode() { return reasonCode; }
    public Long intakeId() { return intakeId; }
}
