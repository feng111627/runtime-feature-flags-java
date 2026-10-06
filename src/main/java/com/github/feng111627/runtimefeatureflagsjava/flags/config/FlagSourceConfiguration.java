package com.github.feng111627.runtimefeatureflagsjava.flags.config;

import com.github.feng111627.runtimefeatureflagsjava.flags.core.FeatureFlagService;
import com.github.feng111627.runtimefeatureflagsjava.flags.source.FlagSource;
import com.github.feng111627.runtimefeatureflagsjava.flags.source.InMemoryFlagSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 默认配置来源与启动时装载。 */
@Configuration
public class FlagSourceConfiguration {

    /** 默认的本地内存来源，优先级 0，可通过 replace() 动态更新。 */
    @Bean
    public InMemoryFlagSource localFlagSource() {
        return new InMemoryFlagSource("local-default", 0);
    }

    /** 应用启动后执行首次加载，让配置立即生效。 */
    @Bean
    public ApplicationRunner initialFlagLoad(FeatureFlagService service) {
        return args -> service.reload();
    }
}
