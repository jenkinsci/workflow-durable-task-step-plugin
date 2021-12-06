package org.jenkinsci.plugins.workflow.support.steps;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Main;
import hudson.Util;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.ResourceList;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueListener;
import hudson.model.queue.SubTask;
import hudson.remoting.ChannelClosedException;
import hudson.remoting.RequestAbortedException;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import hudson.slaves.OfflineCause;
import hudson.slaves.WorkspaceList;
import java.io.IOException;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import static java.util.logging.Level.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import jenkins.model.Jenkins.MasterComputer;
import jenkins.model.NodeListener;
import jenkins.model.queue.AsynchronousExecution;
import jenkins.security.QueueItemAuthenticator;
import jenkins.security.QueueItemAuthenticatorProvider;
import jenkins.util.Timer;
import org.acegisecurity.AccessDeniedException;
import org.acegisecurity.Authentication;
import org.jenkinsci.plugins.durabletask.executors.ContinuableExecutable;
import org.jenkinsci.plugins.durabletask.executors.ContinuedTask;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.QueueItemAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.durable_task.Messages;
import org.jenkinsci.plugins.workflow.support.actions.WorkspaceActionImpl;
import org.jenkinsci.plugins.workflow.support.concurrent.Timeout;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

public class ExecutorStepExecution extends AbstractStepExecutionImpl {

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "deliberately mutable")
    @Restricted(value = NoExternalUse.class)
    public static long TIMEOUT_WAITING_FOR_NODE_MILLIS = Main.isUnitTest ? /* fail faster */ TimeUnit.SECONDS.toMillis(15) : Long.getLong("org.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.timeoutForNodeMillis", TimeUnit.MINUTES.toMillis(5));

    private final ExecutorStep step;
    // TODO perhaps just inline it here? does not do much good as a separate class
    private ExecutorStepDynamicContext state;

    ExecutorStepExecution(StepContext context, ExecutorStep step) {
        super(context);
        this.step = step;
    }

    /**
     * General strategy of this step.
     *
     * 1. schedule {@link PlaceholderTask} into the {@link Queue} (what this method does)
     * 2. when {@link PlaceholderTask} starts running, invoke the closure
     * 3. when the closure is done, let {@link PlaceholderTask} complete
     */
    @Override
    public boolean start() throws Exception {
        final PlaceholderTask task = new PlaceholderTask(this, step.getLabel());
        Queue.WaitingItem waitingItem = Queue.getInstance().schedule2(task, 0).getCreateItem();
        if (waitingItem == null) {
            // There can be no duplicates. But could be refused if a QueueDecisionHandler rejects it for some odd reason.
            throw new IllegalStateException("failed to schedule task");
        }
        getContext().get(FlowNode.class).addAction(new QueueItemActionImpl(waitingItem.getId()));

        Timer.get().schedule(() -> {
            Queue.Item item = Queue.getInstance().getItem(task);
            if (item != null) {
                TaskListener listener;
                try {
                    listener = getContext().get(TaskListener.class);
                } catch (Exception x) { // IOException, InterruptedException
                    LOGGER.log(FINE, "could not print message to build about " + item + "; perhaps it is already completed", x);
                    return;
                }
                listener.getLogger().println("Still waiting to schedule task");
                CauseOfBlockage cob = item.getCauseOfBlockage();
                if (cob != null) {
                    cob.print(listener);
                }
            }
        }, 15, TimeUnit.SECONDS);
        return false;
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        Queue.Item[] items;
        try (ACLContext as = ACL.as(ACL.SYSTEM)) {
            items = Queue.getInstance().getItems();
        }
        LOGGER.log(FINE, "stopping one of {0}", Arrays.asList(items));
        StepContext context = getContext();
        for (Queue.Item item : items) {
            // if we are still in the queue waiting to be scheduled, just retract that
            if (item.task instanceof PlaceholderTask) {
                PlaceholderTask task = (PlaceholderTask) item.task;
                if (task.context.equals(context)) {
                    task.stopping = true;
                    Queue.getInstance().cancel(item);
                    LOGGER.log(FINE, "canceling {0}", item);
                    break;
                } else {
                    LOGGER.log(FINE, "no match on {0} with {1} vs. {2}", new Object[] {item, task.context, context});
                }
            } else {
                LOGGER.log(FINE, "no match on {0}", item);
            }
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            // if we are already running, kill the ongoing activities, which releases PlaceholderExecutable from its sleep loop
            // Similar to Executor.of, but distinct since we do not have the Executable yet:
            COMPUTERS: for (Computer c : j.getComputers()) {
                for (Executor e : c.getExecutors()) {
                    Queue.Executable exec = e.getCurrentExecutable();
                    if (exec instanceof PlaceholderTask.PlaceholderExecutable) {
                        StepContext actualContext = ((PlaceholderTask.PlaceholderExecutable) exec).getParent().context;
                        if (actualContext.equals(context)) {
                            PlaceholderTask.finish(((PlaceholderTask.PlaceholderExecutable) exec).getParent().cookie);
                            LOGGER.log(FINE, "canceling {0}", exec);
                            break COMPUTERS;
                        } else {
                            LOGGER.log(FINE, "no match on {0} with {1} vs. {2}", new Object[] {exec, actualContext, context});
                        }
                    } else {
                        LOGGER.log(FINE, "no match on {0}", exec);
                    }
                }
            }
        }
        // Whether or not either of the above worked (and they would not if for example our item were canceled), make sure we die.
        super.stop(cause);
    }

    @Override public void onResume() {
        super.onResume();
        try {
            Run<?, ?> run = getContext().get(Run.class);
            if (state == null) {
                LOGGER.fine(() -> "No ExecutorStepDynamicContext found for node block in " + run + "; perhaps loading from a historical build record, hoping for the best");
                return;
            }
            state.resume();
        } catch (Exception x) { // JENKINS-40161
            getContext().onFailure(x);
        }
    }

    @Override public String getStatus() {
        // Yet another copy of the same logic; perhaps this should be factored into some method returning a union of Queue.Item and PlaceholderExecutable?
        for (Queue.Item item : Queue.getInstance().getItems()) {
            if (item.task instanceof PlaceholderTask && ((PlaceholderTask) item.task).context.equals(getContext())) {
                return "waiting for " + item.task.getFullDisplayName() + " to be scheduled; blocked: " + item.getWhy();
            }
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            for (Computer c : j.getComputers()) {
                for (Executor e : c.getExecutors()) {
                    Queue.Executable exec = e.getCurrentExecutable();
                    if (exec instanceof PlaceholderTask.PlaceholderExecutable && ((PlaceholderTask.PlaceholderExecutable) exec).getParent().context.equals(getContext())) {
                        return "running on " + c.getName();
                    }
                }
            }
        }
        return "node block appears to be neither running nor scheduled";
    }

    @Extension public static class CancelledItemListener extends QueueListener {

        @Override public void onLeft(Queue.LeftItem li) {
            if (li.isCancelled()) {
                if (li.task instanceof PlaceholderTask) {
                    PlaceholderTask task = (PlaceholderTask) li.task;
                    if (!task.stopping) {
                        task.context.onFailure(new FlowInterruptedException(Result.ABORTED, true, new QueueTaskCancelled()));
                    }
                }
            }
        }

    }

    public static final class QueueTaskCancelled extends CauseOfInterruption {
        @Override public String getShortDescription() {
            return Messages.ExecutorStepExecution_queue_task_cancelled();
        }
    }

    @Extension public static final class RemovedNodeListener extends NodeListener {
        @Override protected void onDeleted(Node node) {
            if (!RemovedNodeCause.ENABLED) {
                return;
            }
            LOGGER.fine(() -> "received node deletion event on " + node.getNodeName());
            Timer.get().schedule(() -> {
                Computer c = node.toComputer();
                if (c == null || c.isOnline()) {
                    LOGGER.fine(() -> "computer for " + node.getNodeName() + " was missing or online, skipping");
                    return;
                }
                LOGGER.fine(() -> "processing node deletion event on " + node.getNodeName());
                for (Executor e : c.getExecutors()) {
                    Queue.Executable exec = e.getCurrentExecutable();
                    if (exec instanceof PlaceholderTask.PlaceholderExecutable) {
                        PlaceholderTask task = ((PlaceholderTask.PlaceholderExecutable) exec).getParent();
                        TaskListener listener = TaskListener.NULL;
                        try {
                            listener = task.context.get(TaskListener.class);
                        } catch (Exception x) {
                            LOGGER.log(Level.WARNING, null, x);
                        }
                        BodyExecution body = task.body != null ? task.body.get() : null;
                        if (body == null) {
                            listener.getLogger().println("Agent " + node.getNodeName() + " was deleted, but do not have a node body to cancel");
                            continue;
                        }
                        listener.getLogger().println("Agent " + node.getNodeName() + " was deleted; cancelling node body");
                        body.cancel(new RemovedNodeCause());
                    }
                }
            }, TIMEOUT_WAITING_FOR_NODE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    public static final class RemovedNodeCause extends CauseOfInterruption {
        @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "deliberately mutable")
        public static boolean ENABLED = Boolean.parseBoolean(System.getProperty(ExecutorStepExecution.class.getName() + ".REMOVED_NODE_DETECTION", "true"));
        @Override public String getShortDescription() {
            return "Agent was removed";
        }
    }

    /** Transient handle of a running executor task. */
    private static final class RunningTask {
        /** null until placeholder executable runs */
        @Nullable AsynchronousExecution execution;
        /** null until placeholder executable runs */
        @Nullable Launcher launcher;
    }

    private static final String COOKIE_VAR = "JENKINS_NODE_COOKIE";

    @ExportedBean
    public static final class PlaceholderTask implements ContinuedTask, Serializable, AccessControlled {

        /** keys are {@link #cookie}s */
        private static final Map<String,RunningTask> runningTasks = new HashMap<>();

        private final ExecutorStepExecution execution;
        private final StepContext context;
        /** Initially set to {@link ExecutorStep#getLabel}, if any; later switched to actual self-label when block runs. */
        private String label;
        /** Shortcut for {@link #run}. */
        private final String runId;
        /**
         * Unique cookie set once the task starts.
         * Serves multiple purposes:
         * identifies whether we have already invoked the body (since this can be rerun after restart);
         * serves as a key for {@link #runningTasks} and {@link Callback} (cannot just have a doneness flag in {@link PlaceholderTask} because multiple copies might be deserialized);
         * and allows {@link Launcher#kill} to work.
         */
        private String cookie;

        /**
         * Needed for {@link BodyExecution#cancel}.
         * {@code transient} because we cannot save a {@link BodyExecution} in {@link PlaceholderTask}:
         * {@code ExecutorPickle} is written to the stream first, which holds a {@link PlaceholderTask},
         * and the {@link BodyExecution} holds {@link PlaceholderTask.Callback} whose {@link WorkspaceList.Lease}
         * is not processed by {@code WorkspaceListLeasePickle} since pickles are not recursive.
         * So we make a best effort and only try to cancel a body within the current session.
         * TODO try to rewrite this mess
         * @see RemovedNodeListener
         */
        private transient @CheckForNull WeakReference<BodyExecution> body;

        /** {@link Authentication#getName} of user of build, if known. */
        private final @CheckForNull String auth;

        /** Flag to remember that {@link #stop} is being called, so {@link CancelledItemListener} can be suppressed. */
        private transient boolean stopping;

        PlaceholderTask(ExecutorStepExecution execution, String label) throws IOException, InterruptedException {
            this.execution = execution;
            this.context = execution.getContext();
            this.label = label;
            runId = context.get(Run.class).getExternalizableId();
            Authentication runningAuth = Jenkins.getAuthentication();
            if (runningAuth.equals(ACL.SYSTEM)) {
                auth = null;
            } else {
                auth = runningAuth.getName();
            }
            LOGGER.log(FINE, "scheduling {0}", this);
        }

        private Object readResolve() {
            if (cookie != null) {
                synchronized (runningTasks) {
                    runningTasks.put(cookie, new RunningTask());
                }
            }
            LOGGER.log(FINE, "deserializing previously scheduled {0}", this);
            return this;
        }

        /**
         * Gives {@link FlowNode}, waiting to be executed  in build {@link Queue}.
         *
         * @return FlowNode instance, could be null.
         */
        public @CheckForNull FlowNode getNode() throws IOException, InterruptedException {
            return context.get(FlowNode.class);
        }

        @Override public Queue.Executable createExecutable() throws IOException {
            return new PlaceholderExecutable();
        }

        @CheckForNull
        public String getCookie() {
            return cookie;
        }

        @Override public Label getAssignedLabel() {
            if (label == null) {
                return null;
            } else if (label.isEmpty()) {
                Jenkins j = Jenkins.getInstanceOrNull();
                if (j == null) {
                    return null;
                }
                return j.getSelfLabel();
            } else {
                return Label.get(label);
            }
        }

        @Override public Node getLastBuiltOn() {
            if (label == null) {
                return null;
            }
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j == null) {
                return null;
            }
            return j.getNode(label);
        }

        @Override public boolean isBuildBlocked() {
            return false;
        }

        @Deprecated
        @Override public String getWhyBlocked() {
            return null;
        }

        @Override public CauseOfBlockage getCauseOfBlockage() {
            return null;
        }

        @Override public boolean isConcurrentBuild() {
            return false;
        }

        @Override public Collection<? extends SubTask> getSubTasks() {
            return Collections.singleton(this);
        }

        @Override public Queue.Task getOwnerTask() {
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j != null && runId != null) { // JENKINS-60389 shortcut
                try (ACLContext context = ACL.as(ACL.SYSTEM)) {
                    Job<?, ?> job = j.getItemByFullName(runId.substring(0, runId.lastIndexOf('#')), Job.class);
                    if (job instanceof Queue.Task) {
                        return (Queue.Task) job;
                    }
                }
            }
            Run<?,?> r = runForDisplay();
            if (r != null && r.getParent() instanceof Queue.Task) {
                return (Queue.Task) r.getParent();
            } else {
                return this;
            }
        }

        @Override public Object getSameNodeConstraint() {
            return null;
        }

        /**
         * Something we can use to check abort and read permissions.
         * Normally this will be a {@link Run}.
         * However if things are badly broken, for example if the build has been deleted,
         * then as a fallback we use the Jenkins root.
         * This allows an administrator to clean up dead queue items and executor cells.
         * TODO make {@link FlowExecutionOwner} implement {@link AccessControlled}
         * so that an implementation could fall back to checking {@link Job} permission.
         */
        @Override public ACL getACL() {
            try {
                if (!context.isReady()) {
                    return Jenkins.get().getACL();
                }
                FlowExecution exec = context.get(FlowExecution.class);
                if (exec == null) {
                    return Jenkins.get().getACL();
                }
                Queue.Executable executable = exec.getOwner().getExecutable();
                if (executable instanceof AccessControlled) {
                    return ((AccessControlled) executable).getACL();
                } else {
                    return Jenkins.get().getACL();
                }
            } catch (Exception x) {
                LOGGER.log(FINE, null, x);
                return Jenkins.get().getACL();
            }
        }

        @Override public void checkAbortPermission() {
            checkPermission(Item.CANCEL);
        }

        @Override public boolean hasAbortPermission() {
            return hasPermission(Item.CANCEL);
        }

        public @CheckForNull Run<?,?> run() {
            try {
                if (!context.isReady()) {
                    return null;
                }
                return context.get(Run.class);
            } catch (Exception x) {
                LOGGER.log(FINE, "broken " + cookie, x);
                finish(cookie); // probably broken, so just shut it down
                return null;
            }
        }

        public @CheckForNull Run<?,?> runForDisplay() {
            Run<?,?> r = run();
            if (r == null && /* not stored prior to 1.13 */runId != null) {
                try (ACLContext context = ACL.as(ACL.SYSTEM)) {
                    return Run.fromExternalizableId(runId);
                }
            }
            return r;
        }

        @Exported
        @Override public String getUrl() {
            // TODO ideally this would be found via FlowExecution.owner.executable, but how do we check for something with a URL? There is no marker interface for it: JENKINS-26091
            Run<?,?> r = runForDisplay();
            return r != null ? r.getUrl() : "";
        }

        @Override public String getDisplayName() {
            // TODO more generic to check whether FlowExecution.owner.executable is a ModelObject
            Run<?,?> r = runForDisplay();
            if (r != null) {
                String runDisplayName = r.getFullDisplayName();
                String enclosingLabel = getEnclosingLabel();
                if (enclosingLabel != null) {
                    return Messages.ExecutorStepExecution_PlaceholderTask_displayName_label(runDisplayName, enclosingLabel);
                } else {
                    return Messages.ExecutorStepExecution_PlaceholderTask_displayName(runDisplayName);
                }
            } else {
                return Messages.ExecutorStepExecution_PlaceholderTask_displayName(runId);
            }
        }

        @Exported
        @Override public String getName() {
            return getDisplayName();
        }

        @Exported
        @Override public String getFullDisplayName() {
            return getDisplayName();
        }

        static String findLabelName(FlowNode flowNode){
            LabelAction la = flowNode.getPersistentAction(LabelAction.class);

            if (la != null) {
                return la.getDisplayName();
            }
            return null;
        }

        /**
         * Similar to {@link #getEnclosingLabel()}.
         * However instead of returning the innermost label including labels inside node blocks this one
         * concatenates all labels found outside the current (node) block
         *
         * As {@link FlowNode#getEnclosingBlocks()} will return the blocks sorted from inner to outer blocks
         * this method will create a string like
         * <code>#innerblock#outerblock for</code> for a script like
         * <pre>
         *     {@code
         *     parallel(outerblock: {
         *         stage('innerblock') {
         *             node {
         *                 // .. do something here
         *             }
         *         }
         *     }
         *     }
         * </pre>
         *
         * In case there's no context available or we get a timeout we'll just return <code>baseLabel</code>
         *
         * */
        private String concatenateAllEnclosingLabels(StringBuilder labelName) {
            if (!context.isReady()) {
                return labelName.toString();
            }
            FlowNode executorStepNode = null;
            try (Timeout t = Timeout.limit(100, TimeUnit.MILLISECONDS)) {
                executorStepNode = context.get(FlowNode.class);
            } catch (Exception x) {
                LOGGER.log(Level.FINE, null, x);
            }

            if (executorStepNode != null) {
                for(FlowNode node: executorStepNode.getEnclosingBlocks()) {
                    String currentLabelName = findLabelName(node);
                    if (currentLabelName != null) {
                        labelName.append("#");
                        labelName.append(currentLabelName);
                    }
                }
            }

            return labelName.toString();
        }

        /**
        * Provide unique key which will be used to prioritize the list of possible build agents to use
        * */
        @Override
        public String getAffinityKey() {
            StringBuilder ownerTaskName = new StringBuilder(getOwnerTask().getName());
            return concatenateAllEnclosingLabels(ownerTaskName);
        }

        /** hash code of list of heads */
        private transient int lastCheckedHashCode;
        private transient String lastEnclosingLabel;
        @Restricted(NoExternalUse.class) // for Jelly
        public @CheckForNull String getEnclosingLabel() {
            if (!context.isReady()) {
                return null;
            }
            FlowNode executorStepNode;
            try (Timeout t = Timeout.limit(100, TimeUnit.MILLISECONDS)) {
                executorStepNode = context.get(FlowNode.class);
            } catch (Exception x) {
                LOGGER.log(Level.FINE, null, x);
                return null;
            }
            if (executorStepNode == null) {
                return null;
            }
            List<FlowNode> heads = executorStepNode.getExecution().getCurrentHeads();
            int headsHash = heads.hashCode(); // deterministic based on IDs of those heads
            if (headsHash == lastCheckedHashCode) {
                return lastEnclosingLabel;
            } else {
                lastCheckedHashCode = headsHash;
                return lastEnclosingLabel = computeEnclosingLabel(executorStepNode, heads);
            }
        }
        private String computeEnclosingLabel(FlowNode executorStepNode, List<FlowNode> heads) {
            for (FlowNode runningNode : heads) {
                // See if this step is inside our node {} block, and track the associated label.
                boolean match = false;
                String enclosingLabel = null;
                int count = 0;
                for (FlowNode n : runningNode.iterateEnclosingBlocks()) {
                    if (enclosingLabel == null) {
                        ThreadNameAction tna = n.getPersistentAction(ThreadNameAction.class);
                        if (tna != null) {
                            enclosingLabel = tna.getThreadName();
                        } else {
                            LabelAction a = n.getPersistentAction(LabelAction.class);
                            if (a != null) {
                                enclosingLabel = a.getDisplayName();
                            }
                        }
                        if (match && enclosingLabel != null) {
                            return enclosingLabel;
                        }
                    }
                    if (n.equals(executorStepNode)) {
                        if (enclosingLabel != null) {
                            return enclosingLabel;
                        }
                        match = true;
                    }
                    if (count++ > 100) {
                        break; // not important enough to bother
                    }
                }
            }
            return null;
        }

        @Override public long getEstimatedDuration() {
            Run<?,?> r = run();
            // Not accurate if there are multiple agents in one build, but better than nothing:
            return r != null ? r.getEstimatedDuration() : -1;
        }

        @Override public ResourceList getResourceList() {
            return new ResourceList();
        }

        @Override public Authentication getDefaultAuthentication() {
            return ACL.SYSTEM;
        }

        @Override public Authentication getDefaultAuthentication(Queue.Item item) {
            return getDefaultAuthentication();
        }

        @Restricted(NoExternalUse.class)
        @Extension(ordinal=959) public static class AuthenticationFromBuild extends QueueItemAuthenticatorProvider {
            @Override public List<QueueItemAuthenticator> getAuthenticators() {
                return Collections.singletonList(new QueueItemAuthenticator() {
                    @Override public Authentication authenticate(Queue.Task task) {
                        if (task instanceof PlaceholderTask) {
                            String auth = ((PlaceholderTask) task).auth;
                            LOGGER.log(FINE, "authenticating {0}", task);
                            if (Jenkins.ANONYMOUS.getName().equals(auth)) {
                                return Jenkins.ANONYMOUS;
                            } else if (auth != null) {
                                User user = User.getById(auth, false);
                                return user != null ? user.impersonate() : Jenkins.ANONYMOUS;
                            }
                        }
                        return null;
                    }
                });
            }
        }

        @Override public boolean isContinued() {
            return cookie != null; // in which case this is after a restart and we still claim the executor
        }

        @Override public String toString() {
            return "ExecutorStepExecution.PlaceholderTask{runId=" + runId + ",label=" + label + ",context=" + context + ",cookie=" + cookie + ",auth=" + auth + '}';
        }

        private static void finish(@CheckForNull final String cookie) {
            if (cookie == null) {
                return;
            }
            synchronized (runningTasks) {
                final RunningTask runningTask = runningTasks.remove(cookie);
                if (runningTask == null) {
                    LOGGER.log(FINE, "no running task corresponds to {0}", cookie);
                    return;
                }
                final AsynchronousExecution execution = runningTask.execution;
                if (execution == null) {
                    // JENKINS-30759: finished before asynch execution was even scheduled
                    return;
                }
                assert runningTask.launcher != null;
                Timer.get().submit(() -> execution.completed(null)); // JENKINS-31614
                Computer.threadPoolForRemoting.submit(() -> { // JENKINS-34542, JENKINS-45553
                    try {
                        runningTask.launcher.kill(Collections.singletonMap(COOKIE_VAR, cookie));
                    } catch (ChannelClosedException x) {
                        // fine, Jenkins was shutting down
                    } catch (RequestAbortedException x) {
                        // agent was exiting; too late to kill subprocesses
                    } catch (Exception x) {
                        LOGGER.log(Level.WARNING, "failed to shut down " + cookie, x);
                    }
                });
            }
        }

        /**
         * Called when the body closure is complete.
         */
        @SuppressFBWarnings(value="SE_BAD_FIELD", justification="lease is pickled")
        private static final class Callback extends BodyExecutionCallback.TailCall {

            private final String cookie;
            @Deprecated
            private WorkspaceList.Lease lease;
            private final ExecutorStepExecution execution;

            Callback(String cookie, ExecutorStepExecution execution) {
                this.cookie = cookie;
                this.execution = execution;
            }

            @Override protected void finished(StepContext context) throws Exception {
                LOGGER.log(FINE, "finished {0}", cookie);
                try {
                    if (execution != null) {
                        WorkspaceList.Lease _lease = ExtensionList.lookupSingleton(ExecutorStepDynamicContext.WorkspaceListLeaseTranslator.class).get(execution.state);
                        if (_lease != null) {
                            _lease.release();
                        }
                    } else {
                        lease.release();
                        lease = null;
                    }
                } finally {
                    finish(cookie);
                }
                if (execution != null) {
                    boolean _stopping = execution.state.task.stopping;
                    execution.state.task.stopping = true;
                    try {
                        Queue.getInstance().cancel(execution.state.task);
                    } finally {
                        execution.state.task.stopping = _stopping;
                    }
                    execution.state = null;
                }
            }

            @Override public void onFailure(StepContext context, Throwable t) {
                try {
                    if (execution != null) {
                        TaskListener listener = context.get(TaskListener.class);
                        if (ExtensionList.lookup(ExecutorStepRetryEligibility.class).stream().anyMatch(e -> e.shouldRetry(t, execution.state.node, execution.step.getLabel(), listener))) {
                            finished(context);
                            execution.start();
                            return;
                        }
                        listener.getLogger().println("No plugin requested a retry of a failed node block running on " + execution.state.node);
                    }
                } catch (Exception x) {
                    t.addSuppressed(x);
                }
                super.onFailure(context, t);
            }

        }

        /**
         * Occupies {@link Executor} while workflow uses this build agent.
         */
        @ExportedBean
        private final class PlaceholderExecutable implements ContinuableExecutable, AccessControlled {

            @Override public void run() {
                TaskListener listener = null;
                Launcher launcher;
                final Run<?, ?> r;
                Computer computer = null;
                try {
                    Executor exec = Executor.currentExecutor();
                    if (exec == null) {
                        throw new IllegalStateException("running task without associated executor thread");
                    }
                    computer = exec.getOwner();
                    // Set up context for other steps inside this one.
                    Node node = computer.getNode();
                    if (node == null) {
                        throw new IllegalStateException("running computer lacks a node");
                    }
                    listener = context.get(TaskListener.class);
                    launcher = node.createLauncher(listener);
                    r = context.get(Run.class);
                    if (cookie == null) {
                        // First time around.
                        cookie = UUID.randomUUID().toString();
                        // Switches the label to a self-label, so if the executable is killed and restarted, it will run on the same node:
                        label = computer.getName();

                        EnvVars env = computer.getEnvironment();
                        env.overrideExpandingAll(computer.buildEnvironment(listener));
                        env.put(COOKIE_VAR, cookie);
                        // Cf. CoreEnvironmentContributor:
                        if (exec.getOwner() instanceof MasterComputer) {
                            env.put("NODE_NAME", node.getSelfLabel().getName()); // mirror https://github.com/jenkinsci/jenkins/blob/89d334145d2755f74f82aad07b5df4119d7fa6ce/core/src/main/java/jenkins/model/CoreEnvironmentContributor.java#L63
                        } else {
                            env.put("NODE_NAME", label);
                        }
                        env.put("EXECUTOR_NUMBER", String.valueOf(exec.getNumber()));
                        env.put("NODE_LABELS", node.getAssignedLabels().stream().map(Object::toString).collect(Collectors.joining(" ")));

                        synchronized (runningTasks) {
                            runningTasks.put(cookie, new RunningTask());
                        }
                        // For convenience, automatically allocate a workspace, like WorkspaceStep would:
                        Job<?,?> j = r.getParent();
                        if (!(j instanceof TopLevelItem)) {
                            throw new Exception(j + " must be a top-level job");
                        }
                        FilePath p = node.getWorkspaceFor((TopLevelItem) j);
                        if (p == null) {
                            throw new IllegalStateException(node + " is offline");
                        }
                        WorkspaceList.Lease lease = computer.getWorkspaceList().allocate(p);
                        FilePath workspace = lease.path;
                        // Cf. AbstractBuild.getEnvironment:
                        env.put("WORKSPACE", workspace.getRemote());
                        final FilePath tempDir = WorkspaceList.tempDir(workspace);
                        if (tempDir != null) {
                            env.put("WORKSPACE_TMP", tempDir.getRemote()); // JENKINS-60634
                        }
                        FlowNode flowNode = context.get(FlowNode.class);
                        if (flowNode != null) {
                            flowNode.addAction(new WorkspaceActionImpl(workspace, flowNode));
                        }
                        listener.getLogger().println("Running on " + ModelHyperlinkNote.encodeTo(node) + " in " + workspace);
                        ExecutorStepDynamicContext state = new ExecutorStepDynamicContext(PlaceholderTask.this, lease, exec);
                        execution.state = state;
                        body = new WeakReference<>(context.newBodyInvoker()
                                .withContexts(env, state)
                                .withCallback(new Callback(cookie, execution))
                                .start());
                        LOGGER.log(FINE, "started {0}", cookie);
                    } else {
                        // just rescheduled after a restart; wait for task to complete
                        LOGGER.log(FINE, "resuming {0}", cookie);
                    }
                } catch (Exception x) {
                    if (computer != null) {
                        for (Computer.TerminationRequest tr : computer.getTerminatedBy()) {
                            x.addSuppressed(tr);
                        }
                        if (listener != null) {
                            OfflineCause oc = computer.getOfflineCause();
                            if (oc != null) {
                                listener.getLogger().println(computer.getDisplayName() + " was marked offline: " + oc);
                            }
                        }
                    }
                    context.onFailure(x);
                    return;
                }
                // wait until the invokeBodyLater call above completes and notifies our Callback object
                synchronized (runningTasks) {
                    LOGGER.log(FINE, "waiting on {0}", cookie);
                    RunningTask runningTask = runningTasks.get(cookie);
                    if (runningTask == null) {
                        LOGGER.log(FINE, "running task apparently finished quickly for {0}", cookie);
                        return;
                    }
                    assert runningTask.execution == null;
                    assert runningTask.launcher == null;
                    runningTask.launcher = launcher;
                    TaskListener _listener = listener;
                    runningTask.execution = new AsynchronousExecution() {
                        @Override public void interrupt(boolean forShutdown) {
                            if (forShutdown) {
                                return;
                            }
                            LOGGER.log(FINE, "interrupted {0}", cookie);
                            // TODO save the BodyExecution somehow and call .cancel() here; currently we just interrupt the build as a whole:
                            Timer.get().submit(() -> { // JENKINS-46738
                                Executor masterExecutor = r.getExecutor();
                                if (masterExecutor != null) {
                                    masterExecutor.interrupt();
                                } else { // anomalous state; perhaps build already aborted but this was left behind; let user manually cancel executor slot
                                    Executor thisExecutor = /* AsynchronousExecution. */getExecutor();
                                    if (thisExecutor != null) {
                                        thisExecutor.recordCauseOfInterruption(r, _listener);
                                    }
                                    completed(null);
                                }
                            });
                        }
                        @Override public boolean blocksRestart() {
                            return false;
                        }
                        @Override public boolean displayCell() {
                            return true;
                        }
                    };
                    throw runningTask.execution;
                }
            }

            @Override public PlaceholderTask getParent() {
                return PlaceholderTask.this;
            }

            @Override public Queue.Executable getParentExecutable() {
                Run<?, ?> b = runForDisplay();
                if (b instanceof Queue.Executable) {
                    return (Queue.Executable) b;
                } else {
                    return null;
                }
            }

            @Exported
            public Integer getNumber() {
                Run<?, ?> r = getParent().runForDisplay();
                return r != null ? r.getNumber() : null;
            }

            @Exported
            public String getFullDisplayName() {
                return getParent().getFullDisplayName();
            }

            @Exported
            public String getDisplayName() {
                return getParent().getDisplayName();
            }

            @Exported
            @Override public long getEstimatedDuration() {
                return getParent().getEstimatedDuration();
            }

            @Exported
            public Long getTimestamp() {
                Run<?, ?> r = getParent().runForDisplay();
                return r != null ? r.getStartTimeInMillis() : null;
            }

            @Override public boolean willContinue() {
                synchronized (runningTasks) {
                    return runningTasks.containsKey(cookie);
                }
            }

            @Restricted(DoNotUse.class) // for Jelly
            public @CheckForNull Executor getExecutor() {
                return Executor.of(this);
            }

            @Restricted(NoExternalUse.class) // for Jelly and toString
            public String getUrl() {
                return PlaceholderTask.this.getUrl(); // we hope this has a console.jelly
            }

            @Exported(name="url")
            public String getAbsoluteUrl() {
                Run<?,?> r = runForDisplay();
                if (r == null) {
                    return "";
                }
                Jenkins j = Jenkins.getInstanceOrNull();
                String base = "";
                if (j != null) {
                    base = Util.removeTrailingSlash(j.getRootUrl()) + "/";
                }
                return base + r.getUrl();
            }

            @Override public String toString() {
                return "PlaceholderExecutable:" + PlaceholderTask.this;
            }

            private static final long serialVersionUID = 1L;

            @Override
            public ACL getACL() {
                return getParent().getACL();
            }

            @Override
            public void checkPermission(Permission permission) throws AccessDeniedException {
                getACL().checkPermission(permission);
            }

            @Override
            public boolean hasPermission(Permission permission) {
                return getACL().hasPermission(permission);
            }
        }

        private static final long serialVersionUID = 1098885580375315588L; // as of 2.12
    }

    private static final class QueueItemActionImpl extends QueueItemAction {
        /**
         * Used to identify the task in the queue, so that its status can be identified.
         */
        private long id;

        QueueItemActionImpl(long id) {
            this.id = id;
        }

        @Override
        @CheckForNull
        public Queue.Item itemInQueue() {
            return Queue.getInstance().getItem(id);
        }
    }

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(ExecutorStepExecution.class.getName());
}
