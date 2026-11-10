package com.onlineexam.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.platform.domain.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class WeChatCodeClient implements WeChatIdentityProvider {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String appId;
    private final String secret;

    WeChatCodeClient(ObjectMapper json, @Value("${WECHAT_APP_ID:}") String appId,
                     @Value("${WECHAT_APP_SECRET:}") String secret) {
        this.json = json;
        this.appId = appId;
        this.secret = secret;
    }

    @Override
    public Subject exchange(String code) {
        if (appId.isBlank() || secret.isBlank()) throw unavailable();
        URI uri = URI.create("https://api.weixin.qq.com/sns/jscode2session?grant_type=authorization_code"
                + "&appid=" + encode(appId) + "&secret=" + encode(secret) + "&js_code=" + encode(code));
        var request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            var response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw unavailable();
            var body = json.readTree(response.body());
            if (body == null || !body.isObject()) throw unavailable();
            int error = body.path("errcode").asInt(0);
            if (error == 40029 || error == 40163) throw ApiException.validation("微信登录码无效或已使用，请重新获取。");
            if (error == 45011) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "微信登录过于频繁，请稍后重试。");
            }
            String openId = body.path("openid").asText("");
            if (error != 0 || openId.isBlank() || openId.length() > 128) throw unavailable();
            return new Subject(appId, openId);
        } catch (InterruptedException error) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (TimeoutException | ExecutionException | IOException error) {
            pending.cancel(true);
            throw unavailable();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "微信服务暂不可用，请稍后重试。");
    }
}
