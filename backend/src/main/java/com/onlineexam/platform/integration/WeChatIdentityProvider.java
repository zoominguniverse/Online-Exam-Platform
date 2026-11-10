package com.onlineexam.platform.integration;

public interface WeChatIdentityProvider {
    record Subject(String appId, String openId) {}

    Subject exchange(String code);
}
