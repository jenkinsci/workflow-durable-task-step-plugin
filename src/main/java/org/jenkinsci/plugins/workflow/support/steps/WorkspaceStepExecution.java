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
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.actions.WorkspaceActionImpl;

public class WorkspaceStepExecution extends AbstractStepExecutionImpl {

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
        if (workspace.getParent() != null) {
            env.put("WORKSPACE_TMP", WorkspaceList.tempDir(workspace).getRemote());
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

    @SuppressFBWarnings(value="SE_BAD_FIELD", justification="lease is pickled")
    private static final class Callback extends BodyExecutionCallback.TailCall {

        private final WorkspaceList.Lease lease;

        Callback(WorkspaceList.Lease lease) {
            this.lease = lease;
        }

        @Override protected void finished(StepContext context) throws Exception {
            lease.release();
        }

    }

    private static final long serialVersionUID = 1L;

}
