package com.codepilot.module.git.policy;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.config.GithubRepositoryProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubRepositoryPolicyTest {

    @Test
    void shouldAllowAllRepositoriesWhenAllowlistIsEmpty() {
        GithubRepositoryPolicy policy = new GithubRepositoryPolicy(new GithubRepositoryProperties());

        assertThat(policy.isRestricted()).isFalse();
        assertThatCode(() -> policy.assertAllowed("any-owner", "any-repo")).doesNotThrowAnyException();
    }

    @Test
    void shouldAllowConfiguredRepositoryCaseInsensitively() {
        GithubRepositoryProperties properties = new GithubRepositoryProperties();
        properties.setAllowedRepositories(List.of("https://github.com/Liche719/codeAireview.git"));
        GithubRepositoryPolicy policy = new GithubRepositoryPolicy(properties);

        assertThat(policy.isRestricted()).isTrue();
        assertThat(policy.isAllowed("liche719", "CODEAIREVIEW")).isTrue();
        assertThatCode(() -> policy.assertAllowed("liche719", "CODEAIREVIEW")).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectRepositoryOutsideAllowlist() {
        GithubRepositoryProperties properties = new GithubRepositoryProperties();
        properties.setAllowedRepositories(List.of("liche719/codeAireview"));
        GithubRepositoryPolicy policy = new GithubRepositoryPolicy(properties);

        assertThat(policy.isAllowed("evil", "repo")).isFalse();
        assertThatThrownBy(() -> policy.assertAllowed("evil", "repo"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("GitHub repository is not allowed: evil/repo");
    }
}
