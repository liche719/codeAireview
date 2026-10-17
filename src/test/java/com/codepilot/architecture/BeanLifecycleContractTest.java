package com.codepilot.architecture;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 生命周期方法的全局约束校验。
 *
 * <h2>为什么需要这个测试</h2>
 * {@code DeadLetterMaintenanceJob} 曾把 {@code MeterRegistry} 写成
 * {@code @PostConstruct} 方法的参数，而 Spring 要求生命周期方法**无参**。
 * 结果：**775 个测试全绿，生产实例启动即崩**：
 *
 * <pre>
 * IllegalStateException: Lifecycle annotation requires a no-arg method:
 *   void ...DeadLetterMaintenanceJob.registerBacklogGauge(MeterRegistry)
 * </pre>
 *
 * <p>它之所以躲过了所有测试，是因为**唯一会创建该 bean 的条件是
 * {@code codepilot.scheduling.enabled=true}**，而集成测试为了跑得快把它设成了 false。
 * 这类"某个 @Conditional bean 从来没被任何测试创建过"的盲区，靠再多单测也补不上——
 * 只能靠一条不依赖上下文的静态检查。</p>
 *
 * <h2>为什么这条检查是对的，而不是凑数的</h2>
 * 它校验的是 Spring 的**硬性约束**（{@code InitDestroyAnnotationBeanPostProcessor}
 * 在启动时直接抛异常），不是风格偏好。任何加进来的新组件都会被自动纳入。
 */
class BeanLifecycleContractTest {

    private static final String BASE_PACKAGE = "com.codepilot";

    @Test
    void lifecycleAnnotationsMustBeOnNoArgMethods() throws Exception {
        List<String> violations = new ArrayList<>();
        int inspected = 0;
        for (Class<?> type : scanComponents()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(PostConstruct.class)
                        && !method.isAnnotationPresent(PreDestroy.class)) {
                    continue;
                }
                inspected++;
                if (method.getParameterCount() > 0) {
                    violations.add(type.getName() + "#" + method.getName()
                            + " takes " + method.getParameterCount() + " parameter(s)");
                }
            }
        }

        assertThat(violations)
                .as("Spring requires @PostConstruct/@PreDestroy methods to take no arguments; "
                        + "inject dependencies via the constructor instead")
                .isEmpty();
        // 防止扫描本身失效（比如包名写错）导致这条检查变成永远通过的空壳。
        assertThat(inspected)
                .as("expected to find lifecycle methods across the codebase, scan may be broken")
                .isGreaterThan(0);
    }

    private List<Class<?>> scanComponents() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // AnnotationTypeFilter 默认也匹配元注解，因此 @Service / @Repository / @Configuration 一并覆盖。
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        Set<BeanDefinition> candidates = scanner.findCandidateComponents(BASE_PACKAGE);
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : candidates) {
            if (candidate.getBeanClassName() != null) {
                classes.add(Class.forName(candidate.getBeanClassName()));
            }
        }
        return classes;
    }
}
