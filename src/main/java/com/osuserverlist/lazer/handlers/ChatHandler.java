package com.osuserverlist.lazer.handlers;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatHandler implements Handler {
    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String path = ctx.path();

        if (path.endsWith("/ack")) {
            ctx.status(200).json(Map.of("silences", Collections.emptyList()));
            return;
        }

        if (path.endsWith("/channels")) {
            Map<String, Object> channel = new LinkedHashMap<>();
            channel.put("channel_id", 1);
            channel.put("name", "#osu");
            channel.put("description", "General chat");
            channel.put("type", "PUBLIC");
            channel.put("moderated", false);
            ctx.status(200).json(List.of(channel));
            return;
        }

        if (path.endsWith("/updates")) {
            ctx.status(200).json(Map.of(
                    "messages", Collections.emptyList(),
                    "presence", Collections.emptyList()
            ));
            return;
        }

        ctx.status(200).json(Collections.emptyMap());
    }
}
