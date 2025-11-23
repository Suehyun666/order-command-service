package com.hts.order.exception;


public abstract class ServerException extends RuntimeException {
    private final int errorCode;

    protected ServerException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
    public int getErrorCode() { return errorCode; }
    public boolean shouldCloseConnection() { return false; }
}
