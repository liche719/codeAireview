package com.codepilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan({
        "com.codepilot.module.review.mapper",
        "com.codepilot.module.audit.mapper",
        "com.codepilot.module.rag.mapper",
        "com.codepilot.module.command.mapper",
        "com.codepilot.module.agent.review.cache"
})
@EnableScheduling
@SpringBootApplication
public class CodePilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodePilotApplication.class, args);
    }
}
