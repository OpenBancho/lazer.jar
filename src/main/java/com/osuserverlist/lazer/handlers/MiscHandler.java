package com.osuserverlist.lazer.handlers;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;

public class MiscHandler implements Handler {
    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String path = ctx.path();

        if (path.contains("seasonal-backgrounds")) {
            ctx.status(200).json(Map.of(
                    "ends_at", "9999-12-31T23:59:59Z",
                    "backgrounds", Collections.emptyList()
            ));
            return;
        }

        if (path.contains("beatmapset-favourites")) {
            ctx.status(200).json(Map.of("beatmapset_ids", Collections.emptyList()));
            return;
        }

        if (path.startsWith("/api/v1/banner/")) {
            String filename = ctx.pathParam("file");
            if (filename.matches("[0-9A-Za-z_.-]+")) {
                java.nio.file.Path file1 = java.nio.file.Path.of("data", "assets", "banners", filename).toAbsolutePath().normalize();
                java.nio.file.Path file2 = java.nio.file.Path.of("..", "bancho.jar", "data", "assets", "banners", filename).toAbsolutePath().normalize();
                java.nio.file.Path target = java.nio.file.Files.isRegularFile(file1) ? file1 : (java.nio.file.Files.isRegularFile(file2) ? file2 : null);
                if (target != null) {
                    ctx.contentType("image/png");
                    ctx.header("Cache-Control", "public, max-age=60");
                    ctx.result(java.nio.file.Files.readAllBytes(target));
                    return;
                }
            }
            ctx.status(404).result("No such banner.");
            return;
        }

        if (path.startsWith("/a/") || path.startsWith("/avatar/")) {
            String id = ctx.pathParam("id");
            String filename = id.endsWith(".png") ? id : (id + ".png");
            java.nio.file.Path file1 = java.nio.file.Path.of("data", "assets", "avatars", filename).toAbsolutePath().normalize();
            java.nio.file.Path file2 = java.nio.file.Path.of("..", "bancho.jar", "data", "assets", "avatars", filename).toAbsolutePath().normalize();
            java.nio.file.Path target = java.nio.file.Files.isRegularFile(file1) ? file1 : (java.nio.file.Files.isRegularFile(file2) ? file2 : null);
            if (target != null) {
                ctx.contentType("image/png");
                ctx.header("Cache-Control", "public, max-age=60");
                ctx.result(java.nio.file.Files.readAllBytes(target));
                return;
            }
            ctx.status(404).result("No such avatar.");
            return;
        }

        ctx.status(200).json(Collections.emptyMap());
    }
}
