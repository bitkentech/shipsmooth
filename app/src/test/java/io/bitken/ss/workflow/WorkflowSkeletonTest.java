package io.bitken.ss.workflow;

import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for the plan-37 task 1 skeleton: confirms the workflow package's
 * core types exist with the expected shape before any orchestration is moved
 * into them.
 */
class WorkflowSkeletonTest {

    @Test
    void workflowServiceInterface_exists() {
        // Must be an interface, not a class — implementations are pluggable.
        assertTrue(WorkflowService.class.isInterface(),
                "WorkflowService must be an interface");
    }

    @Test
    void workflowServiceImpl_constructible() {
        WorkflowServiceImpl impl = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(Paths.get(".")))
                .build()
                .workflowServiceImpl();
        assertNotNull(impl);
        assertTrue(impl instanceof WorkflowService,
                "WorkflowServiceImpl must implement WorkflowService");
    }

    @Test
    void workflowException_carriesErrorCodeAndExitCode() {
        WorkflowException e = new WorkflowException(
                WorkflowErrorCode.INTERNAL_ERROR, "boom");
        assertEquals(WorkflowErrorCode.INTERNAL_ERROR, e.errorCode());
        assertEquals("boom", e.getMessage());
        assertTrue(e.exitCode() != 0, "non-success errors must exit non-zero");
    }

    @Test
    void workflowException_wrapsCauses() {
        Throwable cause = new RuntimeException("inner");
        WorkflowException e = new WorkflowException(
                WorkflowErrorCode.INTERNAL_ERROR, "boom", cause);
        assertSame(cause, e.getCause());
    }

    @Test
    void workflowErrorCode_internalErrorExists() {
        // INTERNAL_ERROR is the catch-all; each migration adds task-specific codes.
        assertNotNull(WorkflowErrorCode.valueOf("INTERNAL_ERROR"));
    }
}
