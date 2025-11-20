package com.wwz.push.sse.controller;

import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Log4j2
@RestController
@RequestMapping("/sse")
@CrossOrigin(origins = "*") // 允许跨域，方便测试
public class SseController {

    // 用于保存所有连接的 SseEmitter，键可以为用户ID或其他唯一标识
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    // 创建一个调度线程池，用于模拟定时推送
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    /**
     * 客户端连接 SSE 端点
     * @param clientId 客户端标识，可以从路径、参数或请求头中获取
     * @return SseEmitter
     */
    @GetMapping(path = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestParam(required = false) String clientId) {
        // 如果没有提供clientId，生成一个简单的
        var finalClientId = Optional.ofNullable(clientId).orElseGet(() -> "client-" + System.currentTimeMillis());

        // 设置连接超时时间，0表示永不超时
        SseEmitter emitter = new SseEmitter(0L);
        this.emitters.put(finalClientId, emitter);

        // 绑定生命周期事件
        emitter.onCompletion(() -> {
            log.info("SSE 连接完成 {}", finalClientId);
            this.emitters.remove(finalClientId);
        });
        emitter.onTimeout(() -> {
            log.info("SSE 连接超时 {}", finalClientId);
            emitter.complete();
            this.emitters.remove(finalClientId);
        });
        emitter.onError(e -> {
            log.info("SSE 连接错误 {}: {}", finalClientId, e.getMessage());
            emitter.complete();
            this.emitters.remove(finalClientId);
        });

        // 发送一个连接成功的初始事件
        try {
            var event = SseEmitter.event()
                    .name("connect") // 事件类型
                    .data("【" + clientId + "】连接成功！") // 数据内容
                    .reconnectTime(5000); // 重连时间（毫秒）
            emitter.send(event);
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        log.info("新的 SSE 客户端连接: {}, 当前连接数: {}", clientId, emitters.size());
        return emitter;
    }

    /**
     * 开始向指定客户端推送时间流
     */
    @PostMapping("/start-time-stream")
    public ResponseEntity<String> startTimeStream(@RequestParam String clientId) {
        var emitter = emitters.get(clientId);
        if (emitter == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("客户端未连接: " + clientId);
        }

        // 每秒推送一次当前时间
        scheduler.scheduleAtFixedRate(() -> {
            try {
                var timeData = "服务器时间: " + java.time.LocalTime.now();
                var event = SseEmitter.event()
                        .id(String.valueOf(System.currentTimeMillis())) // 事件ID
                        .name("time") // 事件类型为 'time'
                        .data(timeData);
                emitter.send(event);
                log.info("向 {} 发送: {}", clientId, timeData);
            } catch (IOException e) {
                log.info("向 {} 发送数据失败，停止推送", clientId);
                scheduler.shutdown(); // 发生错误，停止调度
            }
        }, 0, 1, TimeUnit.SECONDS);

        return ResponseEntity.ok("已开始为 " + clientId + " 推送时间流");
    }

    /**
     * 向所有连接的客户端广播消息
     */
    @PostMapping("/broadcast")
    public ResponseEntity<String> broadcast(@RequestParam String message) {
        var successCount = 0;
        for (var entry : emitters.entrySet()) {
            var clientId = entry.getKey();
            var emitter = entry.getValue();
            try {
                var event = SseEmitter.event()
                        .name("broadcast")
                        .data("广播消息: " + message);
                emitter.send(event);
                successCount++;
                log.info("向 {} 广播成功", clientId);
            } catch (IOException e) {
                log.info("向 {} 广播失败: {}", clientId, e.getMessage());
            }
        }
        return ResponseEntity.ok("广播完成，成功发送给 " + successCount + " 个客户端");
    }

    /**
     * 关闭指定客户端的连接
     */
    @PostMapping("/disconnect")
    public ResponseEntity<String> disconnect(@RequestParam String clientId) {
        var emitter = emitters.get(clientId);
        if (emitter != null) {
            emitter.complete();
            emitters.remove(clientId);
            log.info("已断开连接: {}", clientId);
            return ResponseEntity.ok("已断开连接: " + clientId);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("客户端未找到: " + clientId);
    }
}