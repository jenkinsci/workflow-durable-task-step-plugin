package org.jenkinsci.plugins.workflow.steps.durable_task;

import hudson.AbortException;

import java.io.Serializable;

/**
 * Represents an object that tracks background task
 * forked off by {@code sh(background:true)}
 *
 * <p>
 * This object is serialized along with the pipeline program.
 *
 * @author Kohsuke Kawaguchi
 */
public class BackgroundTask implements Serializable {
    private final DurableTaskStep.Execution execution;

    /*package*/ BackgroundTask(DurableTaskStep.Execution execution) {
        this.execution = execution;
    }

    /*package*/ DurableTaskStep.Execution getExecution() {
        return execution;
    }

    /**
     * Suspends until the process is done.
     *
     * @see BackgroundDurableTaskJoinStep
     */
    public int join() {
        // currently cannot be implemented as an instance method
        // because this module doesn't depend on workflow-cps.
        // use BackgroundDurableTaskJoinStep
        throw new UnsupportedOperationException();
    }

    /**
     * Immediately kills the task.
     */
    public void kill() throws Exception {
        execution.stop(new AbortException());
    }

    /*
        def proc = sh(background:true, script:'android something')

        proc.join()
     */
}
