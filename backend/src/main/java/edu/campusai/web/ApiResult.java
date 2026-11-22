package edu.campusai.web;

import java.util.LinkedHashMap;

/** RuoYi-compatible JSON response envelope. */
public final class ApiResult extends LinkedHashMap<String, Object> {
    private ApiResult(int code, String message) {
        put("code", code);
        put("msg", message);
    }

    public static ApiResult success() {
        return new ApiResult(200, "操作成功");
    }

    public static ApiResult error(int code, String message) {
        return new ApiResult(code, message);
    }

    public ApiResult with(String key, Object value) {
        put(key, value);
        return this;
    }
}
