package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.config.ServerConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class NotificationsHandler implements Handler {
    private final ServerConfig config;

    public NotificationsHandler(ServerConfig config) {
        this.config = config;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("has_more", false);
        resp.put("notifications", Collections.emptyList());
        resp.put("unread_count", 0);

        String scheme = ctx.scheme().equalsIgnoreCase("https") ? "wss" : "ws";
        String host = ctx.host();
        String endpoint = scheme + "://" + host + "/notification-server";
        resp.put("notification_endpoint", endpoint);

        ctx.status(200).json(resp);
    }
}

