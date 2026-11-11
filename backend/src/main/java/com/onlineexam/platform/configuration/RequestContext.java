package com.onlineexam.platform.configuration;
import java.util.UUID;
public final class RequestContext {
    private static final ThreadLocal<UUID> CURRENT=new ThreadLocal<>();
    private RequestContext() {}
    public static UUID id() { UUID value=CURRENT.get();return value==null?UUID.randomUUID():value; }
    static void set(UUID id) { CURRENT.set(id); }
    static void clear() { CURRENT.remove(); }
}
