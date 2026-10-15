package com.codepilot.module.command.approval;

import com.codepilot.module.command.entity.PrCommandTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixApprovalCommandRouterTest {

    @Test
    void shouldRouteExactApprovalWithoutCallingTheModel() {
        FixApprovalService service = mock(FixApprovalService.class);
        PrCommandTask publish = new PrCommandTask();
        publish.setId(30L);
        publish.setStatus("PENDING");
        when(service.approvePublish(10L, 20L)).thenReturn(publish);
        FixApprovalCommandRouter router = new FixApprovalCommandRouter(new FixApprovalCommandParser(), service);
        PrCommandTask approval = new PrCommandTask();
        approval.setId(10L);
        approval.setCommandText("批准发布修复 20");

        assertThat(router.route(approval)).get().asString().contains("publishTaskId=30");
        verify(service).approvePublish(10L, 20L);
    }

    @Test
    void shouldIgnoreNaturalLanguageThatIsNotAnExactControlCommand() {
        FixApprovalCommandRouter router = new FixApprovalCommandRouter(
                new FixApprovalCommandParser(), mock(FixApprovalService.class));
        PrCommandTask task = new PrCommandTask();
        task.setId(10L);
        task.setCommandText("这个补丁不错，帮我处理一下");

        assertThat(router.route(task)).isEmpty();
    }
}
