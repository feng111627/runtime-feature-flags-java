package com.github.feng111627.runtimefeatureflagsjava.flags.api;

import com.github.feng111627.runtimefeatureflagsjava.flags.core.FeatureFlagService;
import com.github.feng111627.runtimefeatureflagsjava.flags.core.FlagSnapshot;
import com.github.feng111627.runtimefeatureflagsjava.flags.model.EvaluationResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 开关判定与管理的 REST 接口。 */
@RestController
@RequestMapping("/api/flags")
public class FeatureFlagController {

    private final FeatureFlagService service;

    public FeatureFlagController(FeatureFlagService service) {
        this.service = service;
    }

    /** 判定某个开关对指定用户是否命中。 */
    @GetMapping("/{flagName}/evaluate")
    public EvaluationResult evaluate(@PathVariable String flagName,
                                     @RequestParam(required = false) String userId) {
        return service.evaluate(flagName, userId);
    }

    /** 从所有来源重新加载并原子发布。 */
    @PostMapping("/reload")
    public FlagSnapshot reload() {
        return service.reload();
    }

    /** 回退到上一次有效版本。 */
    @PostMapping("/rollback")
    public FlagSnapshot rollback() {
        return service.rollback();
    }

    /** 查看当前生效快照。 */
    @GetMapping("/snapshot")
    public FlagSnapshot snapshot() {
        return service.currentSnapshot();
    }
}
