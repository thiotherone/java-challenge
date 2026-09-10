package com.mendel.transactions.api.dto;

/** Acknowledgement body required by the challenge specification: {@code {"status":"ok"}}. */
public record StatusResponse(String status) {

    private static final StatusResponse OK = new StatusResponse("ok");

    public static StatusResponse ok() {
        return OK;
    }
}
