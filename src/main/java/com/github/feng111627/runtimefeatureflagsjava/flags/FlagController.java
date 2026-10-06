package com.github.feng111627.runtimefeatureflagsjava.flags;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运行时查询与更新开关的 REST 入口。
 *
 * 配置来源（优先级从低到高）：
 * 1. 本地 JSON 文件（flags.file 指定，可选），优先级 10；
 * 2. 运行时通过 POST /flags/push 推送的内存配置，优先级 20。
 */
@RestController
@RequestMapping("/flags")
public class FlagController {

    private final FeatureFlagService service;
    private final InMemoryFlagSource pushSource = new InMemoryFlagSource("rest-push", 20);
    private final Path filePath;

    public FlagController(FeatureFlagService service,
                          @Value("${flags.file:}") String filePath) {
        this.service = service;
        this.filePath = filePath == null || filePath.isBlank() ? null : Path.of(filePath);
    }

    /** 判定某个开关对指定标识的取值，返回可解释的判定结果 */
    @GetMapping("/{flag}/evaluate")
    public Evaluation evaluate(@PathVariable String flag,
                               @RequestParam String id,
                               @RequestParam Map<String, String> attributes) {
        attributes.remove("id");
        return service.evaluate(flag, id, attributes);
    }

    /** 推送一批开关定义（高优先级来源），并触发整批刷新 */
    @PostMapping("/push")
    public ResponseEntity<?> push(@RequestBody Map<String, FlagDefinition> flags) {
        pushSource.setFlags(flags);
        return refresh();
    }

    /** 从所有来源重新加载并整批生效 */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh() {
        try {
            long version = service.refresh(sources());
            return ResponseEntity.ok(Map.of("version", version));
        } catch (FlagConfigException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "errorType", e.getErrorType().name(),
                    "message", e.getMessage()));
        }
    }

    /** 回退到上一次有效版本 */
    @PostMapping("/rollback")
    public ResponseEntity<?> rollback() {
        try {
            service.rollback();
            return ResponseEntity.ok(Map.of("version", service.currentVersion()));
        } catch (FlagConfigException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "errorType", e.getErrorType().name(),
                    "message", e.getMessage()));
        }
    }

    /** 查看当前生效版本与全部开关定义 */
    @GetMapping
    public Map<String, Object> current() {
        return Map.of(
                "version", service.currentVersion(),
                "flags", service.snapshot().flags());
    }

    private List<FlagSource> sources() {
        List<FlagSource> sources = new ArrayList<>();
        if (filePath != null) {
            sources.add(new JsonFileFlagSource("json-file", 10, filePath));
        }
        sources.add(pushSource);
        return sources;
    }
}
