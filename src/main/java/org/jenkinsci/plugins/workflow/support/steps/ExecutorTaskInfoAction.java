package org.jenkinsci.plugins.workflow.support.steps;

import hudson.FilePath;
import hudson.model.InvisibleAction;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.actions.FlowNodeAction;
import org.jenkinsci.plugins.workflow.actions.PersistentAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Records information on the {@link ExecutorStepExecution.PlaceholderTask} for a {@code node} block.
 *
 * TODO: Decide whether this is general enough that it should move to workflow-support alongside
 * {@link org.jenkinsci.plugins.workflow.support.actions.WorkspaceActionImpl}.
 */
public class ExecutorTaskInfoAction extends InvisibleAction implements FlowNodeAction, PersistentAction {
    private static final long serialVersionUID = 1;

    private String agent;
    private String whyBlocked;
    // Initialized at -1 for "not started yet".
    private long whenStartedOrCanceled = -1L;

    private transient FlowNode parent;

    ExecutorTaskInfoAction(FlowNode parent) {
        this.parent = parent;
    }

    ExecutorTaskInfoAction(@Nonnull String whyBlocked, FlowNode parent) {
        this(parent);
        this.whyBlocked = whyBlocked;
    }

    public FlowNode getParent() {
        return parent;
    }

    @Override
    public void onLoad(FlowNode parent) {
        this.parent = parent;
    }

    void setAgent(@Nonnull FilePath workspace) {
        // Because we're not blocked any more at this point!
        this.whyBlocked = null;
        this.whenStartedOrCanceled = System.currentTimeMillis();
        this.agent = FilePathUtils.getNodeName(workspace);
    }

    @CheckForNull
    public String getAgent() {
        return agent;
    }

    void setWhyBlocked(@Nonnull String whyBlocked) {
        this.whyBlocked = whyBlocked;
    }

    @CheckForNull
    public String getWhyBlocked() {
        return whyBlocked;
    }

    public long getWhenStartedOrCanceled() {
        return whenStartedOrCanceled;
    }

    void cancelTask() {
        this.whyBlocked = null;
        this.whenStartedOrCanceled = System.currentTimeMillis();
    }

    public boolean isQueued() {
        return whyBlocked != null && whenStartedOrCanceled == -1;
    }

    public boolean isRunning() {
        return agent != null;
    }

    public boolean isCanceled() {
        return agent == null && whyBlocked == null && whenStartedOrCanceled > -1;
    }
}
