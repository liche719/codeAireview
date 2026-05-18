package com.codepilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan({
        "com.codepilot.module.review.mapper",
        "com.codepilot.module.audit.mapper",
        "com.codepilot.module.rag.mapper"
})
@SpringBootApplication
public class CodePilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodePilotApplication.class, args);
    }
}
