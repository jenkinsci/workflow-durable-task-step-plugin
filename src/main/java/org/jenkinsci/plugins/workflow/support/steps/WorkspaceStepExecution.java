package org.jenkinsci.plugins.workflow.support.steps;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.slaves.WorkspaceList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.actions.WorkspaceActionImpl;

public class WorkspaceStepExecution extends AbstractStepExecutionImpl {

    private static final Logger LOGGER = Logger.getLogger(WorkspaceStepExecution.class.getName());

    @SuppressFBWarnings(value="SE_TRANSIENT_FIELD_NOT_RESTORED", justification="only used from #start")
    private transient final String dir;

    WorkspaceStepExecution(StepContext context, String dir) {
        super(context);
        this.dir = dir;
    }

    @Override
    public boolean start() throws Exception {
        Job<?,?> job = getContext().get(Run.class).getParent();
        if (!(job instanceof TopLevelItem)) {
            throw new Exception(job + " must be a top-level job");
        }
        Computer computer = getContext().get(Computer.class);
        Node node = computer.getNode();
        if (node == null) {
            throw new Exception("computer does not correspond to a live node");
        }
        WorkspaceList.Lease lease;
        if (dir == null) {
            FilePath baseWorkspace = node.getWorkspaceFor((TopLevelItem) job);
            if (baseWorkspace == null) {
                throw new IllegalStateException(node + " is offline");
            }
            lease = computer.getWorkspaceList().allocate(baseWorkspace);
        } else {
            FilePath rootPath = node.getRootPath();
            if (rootPath == null) {
                throw new IllegalStateException(node + " is offline");
            }
            FilePath baseWorkspace = rootPath.child(dir);
            lease = computer.getWorkspaceList().allocate(baseWorkspace);
        }
        FilePath workspace = lease.path; // may be baseWorkspace + @2, @3, etc.
        FlowNode flowNode = getContext().get(FlowNode.class);
        flowNode.addAction(new WorkspaceActionImpl(workspace, flowNode));
        getContext().get(TaskListener.class).getLogger().println("Running in " + workspace);
        Map<String, String> env = new HashMap<>();
        env.put("WORKSPACE", workspace.getRemote());
        final FilePath tempDir = WorkspaceList.tempDir(workspace);
        if (tempDir != null) {
            env.put("WORKSPACE_TMP", tempDir.getRemote());
        }
        getContext().newBodyInvoker()
                .withContexts(
                    EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), EnvironmentExpander.constant(env)),
                    FilePathDynamicContext.createContextualObject(workspace))
                .withCallback(new Callback(lease))
                .start();
        return false;
    }

    @Deprecated // for serial compatibility only
    private static final class ExpanderImpl extends EnvironmentExpander {
        private static final long serialVersionUID = 1;
        private final String path;
        private ExpanderImpl(String path) {
            this.path = path;
        }
        @Override public void expand(EnvVars env) throws IOException, InterruptedException {
            env.override("WORKSPACE", path);
        }
    }

    private static final class Callback extends BodyExecutionCallback.TailCall {

        private transient WorkspaceList.Lease lease;
        private final String node;
        private final String path;

        Callback(WorkspaceList.Lease lease) {
            this.lease = lease;
            node = FilePathUtils.getNodeName(lease.path);
            path = lease.path.getRemote();
        }

        @Override protected void finished(StepContext context) throws Exception {
            if (lease == null) { // after restart, unless using historical pickled version
                FilePath fp = FilePathUtils.find(node, path);
                if (fp == null) {
                    LOGGER.fine(() -> "can no longer find " + path + " on " + node + " to release");
                    // Should be harmless: no one could be waiting for a lock if the agent is not even online anyway.
                    return;
                }
                LOGGER.fine(() -> "recreating lease on " + fp);
                Computer c = fp.toComputer();
                if (c == null) {
                    LOGGER.warning(() -> node + " no longer connected");
                    return;
                }
                // See ExecutorStepDynamicContext for background:
                lease = c.getWorkspaceList().allocate(fp);
                if (!lease.path.equals(fp)) {
                    lease.release();
                    LOGGER.warning(() -> "JENKINS-37121: something already locked " + fp + " != " + lease.path);
                    return;
                }
            }
            lease.release();
        }

        private static final long serialVersionUID = 525857611466436091L;

    }

    private static final long serialVersionUID = 1L;

}
