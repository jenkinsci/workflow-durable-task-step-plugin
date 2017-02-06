package org.jenkinsci.plugins.workflow.steps.durable_task;

import hudson.Extension;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Set;

/**
 * Waits for the task forked in {@code sh(background:true, ...)} to complete
 *
 * TODO: native timeout support. in the mean time, combine with the timeout step
 *
 * @author Kohsuke Kawaguchi
 * @see BackgroundTask
 */
public class BackgroundDurableTaskJoinStep extends Step {
    private final BackgroundTask t;

    @DataBoundConstructor
    public BackgroundDurableTaskJoinStep(BackgroundTask t) {
        this.t = t;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, t.getExecution());
    }

    static final class Execution extends AbstractStepExecutionImpl  {
        private DurableTaskStep.Execution task;
        public Execution(StepContext context, DurableTaskStep.Execution t) {
            super(context);
            this.task = t;
        }

        @Override
        public boolean start() throws Exception {
            task.addCompletionHandler(getContext());
            return false;
        }

        @Override
        public void stop(@Nonnull Throwable cause) throws Exception {
            // interrupting this step shouldn't cause the process to die
            // DO NOT: task.stop(cause);
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.emptySet();
        }

        @Override
        public String getFunctionName() {
            return "backgroundDurableTaskJoin";
        }

        /**
         * Marking as advanced for now since this step is
         * meant to be used in {@link BackgroundTask#join()}
         *
         * If we are to open this up to the general audience
         * it should get a better name
         */
        @Override
        public boolean isAdvanced() {
            return true;
        }
    }
}
