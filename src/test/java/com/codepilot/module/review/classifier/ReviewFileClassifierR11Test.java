package com.codepilot.module.review.classifier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R11：i18n / vendor / 生成物三类路径的分类。
 *
 * <p>这里最需要防的不是"漏判"，而是<b>过度匹配导致漏审</b>：
 * vendor 与生成物会被<b>跳过</b>，一旦判定过宽，手写代码就会被静默排除在审查之外。
 * 因此下面有相当一部分是反向断言（像 {@code vendored/} 这种目录<b>不该</b>命中）。</p>
 */
class ReviewFileClassifierR11Test {

    // ---------------- i18n ----------------

    @Test
    void shouldRecognizeI18nPaths() {
        assertThat(ReviewFileClassifier.isI18nPath("src/main/resources/i18n/messages.properties")).isTrue();
        assertThat(ReviewFileClassifier.isI18nPath("app/src/locales/en.json")).isTrue();
        assertThat(ReviewFileClassifier.isI18nPath("lib/locale/fr.po")).isTrue();
        assertThat(ReviewFileClassifier.isI18nPath("web/lang/zh-CN.ts")).isTrue();
        assertThat(ReviewFileClassifier.isI18nPath("translations/de.xliff")).isTrue();
        assertThat(ReviewFileClassifier.isI18nPath("src/main/resources/messages_zh_CN.properties")).isTrue();
        assertThat(ReviewFileClassifier.isI18nPath("conf/messages.properties")).isTrue();
    }

    @Test
    void shouldNotTreatOrdinarySourcesAsI18n() {
        assertThat(ReviewFileClassifier.isI18nPath("src/main/java/Demo.java")).isFalse();
        assertThat(ReviewFileClassifier.isI18nPath("src/main/java/com/example/LanguageService.java")).isFalse();
        assertThat(ReviewFileClassifier.isI18nPath("docs/i18n-guide.md")).isFalse();
    }

    // ---------------- vendored ----------------

    @Test
    void shouldRecognizeVendoredPaths() {
        assertThat(ReviewFileClassifier.isVendoredPath("vendor/github.com/pkg/errors/errors.go")).isTrue();
        assertThat(ReviewFileClassifier.isVendoredPath("third_party/zlib/zlib.c")).isTrue();
        assertThat(ReviewFileClassifier.isVendoredPath("third-party/foo/bar.js")).isTrue();
        assertThat(ReviewFileClassifier.isVendoredPath("frontend/node_modules/left-pad/index.js")).isTrue();
        assertThat(ReviewFileClassifier.isVendoredPath("web/bower_components/jquery/jquery.js")).isTrue();
    }

    /**
     * 反向断言：这些是<b>手写</b>代码，绝不能被当成第三方而跳过。
     *
     * <p>用 {@code contains("vendor")} 这种朴素写法就会把它们误判掉——
     * 这正是"过度跳过导致漏审"的具体形态。</p>
     */
    @Test
    void shouldNotTreatHandWrittenCodeAsVendored() {
        assertThat(ReviewFileClassifier.isVendoredPath("src/main/java/com/example/vendored/License.java")).isFalse();
        assertThat(ReviewFileClassifier.isVendoredPath("src/main/java/com/example/VendorService.java")).isFalse();
        assertThat(ReviewFileClassifier.isVendoredPath("src/main/java/com/example/thirdparty/Foo.java")).isFalse();
        assertThat(ReviewFileClassifier.isVendoredPath("docs/vendor-selection.md")).isFalse();
    }

    // ---------------- generated ----------------

    @Test
    void shouldRecognizeGeneratedArtifacts() {
        assertThat(ReviewFileClassifier.isGeneratedPath("api/service.pb.go")).isTrue();
        assertThat(ReviewFileClassifier.isGeneratedPath("api/service.pb.grpc.go")).isTrue();
        assertThat(ReviewFileClassifier.isGeneratedPath("proto/user_pb2.py")).isTrue();
        assertThat(ReviewFileClassifier.isGeneratedPath("proto/user_pb2_grpc.py")).isTrue();
        assertThat(ReviewFileClassifier.isGeneratedPath("lib/model.g.dart")).isTrue();
        assertThat(ReviewFileClassifier.isGeneratedPath("lib/model.freezed.dart")).isTrue();
        assertThat(ReviewFileClassifier.isGeneratedPath("Forms/MainForm.Designer.cs")).isTrue();
        assertThat(ReviewFileClassifier.isGeneratedPath("src/Api.generated.ts")).isTrue();
        assertThat(ReviewFileClassifier.isGeneratedPath("pkg/zz_generated.deepcopy.go")).isTrue();
    }

    /**
     * 反向断言：{@code generated} 只作为<b>后缀的一部分</b>才成立。
     *
     * <p>不能因为路径里出现 "generated" 就跳过——那会把
     * {@code CodeGenerator.java}、{@code generators/} 这类<b>生成器本身的代码</b>排除掉，
     * 而生成器恰恰是最该审的。</p>
     */
    @Test
    void shouldNotSkipTheGeneratorItself() {
        assertThat(ReviewFileClassifier.isGeneratedPath("src/main/java/com/example/CodeGenerator.java")).isFalse();
        assertThat(ReviewFileClassifier.isGeneratedPath("tools/generators/openapi/main.go")).isFalse();
        assertThat(ReviewFileClassifier.isGeneratedPath("src/main/java/com/example/generated/Hand.java")).isFalse();
    }
}
