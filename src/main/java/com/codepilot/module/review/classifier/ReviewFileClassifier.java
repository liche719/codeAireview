package com.codepilot.module.review.classifier;

import java.util.Locale;

public final class ReviewFileClassifier {

    private ReviewFileClassifier() {
    }

    public static boolean isSourcePath(String path) {
        String normalized = normalizePath(path);
        return normalized.startsWith("src/main/")
                || normalized.endsWith(".java")
                || normalized.endsWith(".kt")
                || normalized.endsWith(".go")
                || normalized.endsWith(".ts")
                || normalized.endsWith(".tsx")
                || normalized.endsWith(".js")
                || normalized.endsWith(".jsx")
                || normalized.endsWith(".py");
    }

    public static boolean isProductionCodePath(String path) {
        return isSourcePath(path) && !isTestPath(path);
    }

    public static boolean isTestPath(String path) {
        String normalized = normalizePath(path);
        return normalized.contains("/test/")
                || normalized.contains("/tests/")
                || normalized.endsWith("test.java")
                || normalized.endsWith("tests.java")
                || normalized.endsWith(".spec.ts")
                || normalized.endsWith(".test.ts")
                || normalized.endsWith(".spec.tsx")
                || normalized.endsWith(".test.tsx")
                || normalized.endsWith(".spec.js")
                || normalized.endsWith(".test.js");
    }

    public static boolean isDatabasePath(String path) {
        String normalized = normalizePath(path);
        return normalized.contains("/db/migration/")
                || normalized.contains("/migrations/")
                || normalized.endsWith(".sql")
                || normalized.endsWith("mapper.xml");
    }

    public static boolean isSecuritySensitivePath(String path) {
        String normalized = normalizePath(path);
        return normalized.contains("security")
                || normalized.contains("auth")
                || normalized.contains("permission")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential");
    }

    public static boolean isConfigurationPath(String path) {
        String normalized = normalizePath(path);
        return normalized.endsWith(".yml")
                || normalized.endsWith(".yaml")
                || normalized.endsWith(".properties")
                || normalized.endsWith(".toml")
                || normalized.endsWith(".env")
                || normalized.endsWith("package.json")
                || normalized.endsWith("tsconfig.json")
                || normalized.endsWith(".eslintrc.json")
                || normalized.contains("config.json")
                || normalized.startsWith(".github/workflows/")
                || normalized.equals("dockerfile")
                || normalized.endsWith("/dockerfile")
                || normalized.contains("docker-compose");
    }

    public static boolean isDependencyManifestPath(String path) {
        String fileName = fileName(path);
        return fileName.equals("pom.xml")
                || fileName.equals("build.gradle")
                || fileName.equals("build.gradle.kts")
                || fileName.equals("settings.gradle")
                || fileName.equals("settings.gradle.kts")
                || fileName.equals("gradle.properties")
                || fileName.equals("package.json")
                || fileName.equals("package-lock.json")
                || fileName.equals("yarn.lock")
                || fileName.equals("pnpm-lock.yaml")
                || fileName.equals("go.mod")
                || fileName.equals("go.sum")
                || fileName.equals("requirements.txt")
                || fileName.equals("pyproject.toml")
                || fileName.equals("poetry.lock");
    }

    public static boolean isPublicApiPath(String path) {
        String normalized = normalizePath(path);
        return normalized.contains("/controller/")
                || normalized.contains("/controllers/")
                || normalized.contains("/api/")
                || normalized.contains("/dto/")
                || normalized.contains("/request/")
                || normalized.contains("/response/")
                || normalized.contains("/graphql/")
                || normalized.contains("/openapi/")
                || normalized.contains("/swagger/")
                || normalized.endsWith(".proto")
                || normalized.endsWith(".graphql")
                || normalized.endsWith(".graphqls");
    }

    public static boolean isDocumentationPath(String path) {
        String normalized = normalizePath(path);
        return normalized.endsWith(".md")
                || normalized.endsWith(".adoc")
                || normalized.endsWith(".rst")
                || normalized.startsWith("docs/");
    }

    public static String fileName(String path) {
        String normalized = normalizePath(path);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    /**
     * i18n / 资源包路径（R11）。
     *
     * <p><b>刻意只降优先级、不跳过。</b>翻译错误（占位符不匹配、漏翻、键名拼错）
     * 是真实缺陷，跳过等于漏审。但这类文件量通常很大且审查价值低于生产代码，
     * 所以给它一个负分，让预算优先分配给别处。</p>
     */
    public static boolean isI18nPath(String path) {
        String normalized = normalizePath(path);
        String fileName = fileName(normalized);
        return normalized.contains("/i18n/")
                || normalized.contains("/locale/")
                || normalized.contains("/locales/")
                || normalized.contains("/lang/")
                || normalized.contains("/translations/")
                || normalized.contains("/messages/")
                || fileName.startsWith("messages")
                || fileName.endsWith(".po")
                || fileName.endsWith(".pot")
                || fileName.endsWith(".xliff")
                || fileName.endsWith(".xlf");
    }

    /**
     * 第三方 vendor 代码（R11）。
     *
     * <p>这些目录里的代码不由本仓库维护：提意见既改不动、也不该改。
     * 跳过它们是安全的——真正需要警惕的是"过度跳过导致漏审"，而 vendor 目录不属于这一类。</p>
     */
    public static boolean isVendoredPath(String path) {
        String normalized = normalizePath(path);
        return containsDirectory(normalized, "vendor")
                || containsDirectory(normalized, "third_party")
                || containsDirectory(normalized, "third-party")
                || containsDirectory(normalized, "node_modules")
                || containsDirectory(normalized, "bower_components");
    }

    /**
     * 代码生成器产物（R11）。
     *
     * <p>只按<b>命名约定</b>判断（各生成器的固定后缀），不按目录名：
     * 一个位于 {@code generated} 子目录下的文件，在路径上无法与手写代码可靠区分，
     * 而命名后缀是生成器自己保证的。</p>
     *
     * <p>改生成物没有意义——下次生成就被覆盖。正确处置是去改生成源，
     * 这也是它单独占一个 skip 分类的原因。</p>
     */
    public static boolean isGeneratedPath(String path) {
        String fileName = fileName(path);
        return fileName.endsWith(".pb.go")
                || fileName.endsWith(".pb.grpc.go")
                || fileName.endsWith("_pb2.py")
                || fileName.endsWith("_pb2_grpc.py")
                || fileName.endsWith(".g.dart")
                || fileName.endsWith(".freezed.dart")
                || fileName.endsWith(".designer.cs")
                || fileName.endsWith(".generated.cs")
                || fileName.endsWith(".generated.java")
                || fileName.endsWith(".generated.ts")
                || fileName.endsWith(".generated.kt")
                || fileName.contains(".generated.")
                || fileName.startsWith("zz_generated.");
    }

    /**
     * 路径中是否含指定目录段。
     *
     * <p>用<b>整段匹配</b>而不是 {@code contains("vendor")}：后者会把
     * {@code src/main/java/.../vendored/License.java} 之类的手写代码误判成第三方代码。
     * 这正是"过度跳过导致漏审"的一条真实入口。</p>
     */
    private static boolean containsDirectory(String normalizedPath, String directory) {
        return normalizedPath.startsWith(directory + "/")
                || normalizedPath.contains("/" + directory + "/")
                || normalizedPath.endsWith("/" + directory);
    }

    public static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }
}
