package com.library.service;

public class BookCopyActionException extends RuntimeException {
    private final int status; private final String reasonCode; private final Long requestId,copyId; private final Integer ordinal;
    public BookCopyActionException(int status,String reason,String message,Long requestId,Long copyId,Integer ordinal) {
        super(message);this.status=status;this.reasonCode=reason;this.requestId=requestId;this.copyId=copyId;this.ordinal=ordinal;
    }
    public int status(){return status;} public String reasonCode(){return reasonCode;} public Long requestId(){return requestId;}
    public Long copyId(){return copyId;} public Integer ordinal(){return ordinal;}
}
