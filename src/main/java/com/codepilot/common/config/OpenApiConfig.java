package com.codepilot.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class OpenApiConfig implements WebMvcConfigurer {

    @Bean
    public OpenAPI codePilotOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CodePilot AI Review API")
                        .description("GitHub PR intelligent code review backend")
                        .version("0.0.1"));
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/doc.html", "/swagger-ui/index.html");
    }
}
