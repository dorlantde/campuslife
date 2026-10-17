package com.campuslife.common.web;

import java.util.UUID;
import org.slf4j.MDC;

public final class TraceContext {
    public static final String KEY = "traceId";

    private TraceContext() {
    }

    public static String currentId() {
        String traceId = MDC.get(KEY);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}
