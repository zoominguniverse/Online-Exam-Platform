package com.onlineexam.platform.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class Contracts {
    private Contracts() {}
    public static void fields(JsonNode body, Set<String> allowed, Set<String> required) {
        if (body == null || !body.isObject()) throw ApiException.validation("请求必须是对象。");
        var names = new java.util.HashSet<String>();
        body.fieldNames().forEachRemaining(names::add);
        if (!allowed.containsAll(names) || !names.containsAll(required)) throw ApiException.validation("请求字段缺失或包含不可修改字段。");
    }
    public static int version(JsonNode body) {
        var value = body.path("expectedVersion");
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) throw ApiException.validation("expectedVersion 无效。");
        return value.asInt();
    }
    public static UUID uuid(JsonNode value) {
        try { if (value == null || !value.isTextual()) throw new IllegalArgumentException(); return UUID.fromString(value.asText()); }
        catch (IllegalArgumentException error) { throw ApiException.validation("UUID 标识无效。"); }
    }
    public static String text(JsonNode body, String name, int min, int max) {
        var value = body.path(name);
        if (!value.isTextual() || value.asText().length() < min || value.asText().length() > max
                || value.asText().indexOf(0) >= 0) throw ApiException.validation(name + " 长度或格式无效。");
        return value.asText();
    }
    public static String key(String key) {
        if (key == null || key.isEmpty() || key.length() > 128 || key.chars().anyMatch(ch -> ch < 33 || ch > 126))
            throw ApiException.validation("Idempotency-Key 需要 1–128 个可打印 ASCII 字符。");
        return key;
    }
    public static String hash(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(ordered(value).toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static JsonNode ordered(JsonNode value) {
        if (value.isObject()) {
            var names = new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            var object = JsonNodeFactory.instance.objectNode(); names.forEach(name -> object.set(name, ordered(value.get(name)))); return object;
        }
        if (value.isArray()) { var array = JsonNodeFactory.instance.arrayNode(); value.forEach(item -> array.add(ordered(item))); return array; }
        return value;
    }
}
