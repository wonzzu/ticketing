package com.ticketing.outbox.exception;

public class DuplicateMessageException extends RuntimeException {

    public DuplicateMessageException(String consumerType, String messageId, Throwable cause) {
        super("이미 처리된 메시지입니다. consumerType=%s, messageId=%s"
                .formatted(consumerType, messageId), cause);
    }
}
