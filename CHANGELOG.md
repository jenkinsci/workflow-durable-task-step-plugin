# Changelog

* For newer versions, see [GitHub Releases](https://github.com/jenkinsci/workflow-durable-task-step-plugin/releases)

## 2.39

Release date: 2021-05-10

- Internal: Jenkins terminology update ([JENKINS-65398](https://issues.jenkins.io/browse/JENKINS-65398))

## 2.38

Release date: 2021-03-03

- Fix: `ExecutorStepTest` fails due to changes in `LoadBalancer` for Jenkins versions > 2.65 ([PR #150](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/150))
- Fix: Typo in resource directory for PowerShellCoreScriptStep ([PR #149](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/149))
- Update user-facing documentation to use build agent terminology ([PR #148](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/148))

## 2.37

Release date: 2020-11-18

- Fix: Prevent build widget from breaking in cases where users have read access to a Pipeline job but not its parent ([JENKINS-63486](https://issues.jenkins.io/browse/JENKINS-63486))
- Internal: Fix typo in changelog ([PR #142](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/142))

## 2.36
2020 Aug 17

- New feature: Add support for global build step env var filters ([JENKINS-62014](https://issues.jenkins-ci.org/browse/JENKINS-62014))
- New feature: Bind an environment variable named WORKSPACE_TMP in the `node` and `ws` steps that points to a temporary directory associated with the workspace as long as the workspace is not a filesystem root ([JENKINS-60634](https://issues.jenkins-ci.org/browse/JENKINS-60634), [JENKINS-61197](https://issues.jenkins-ci.org/browse/JENKINS-61197))
- Fix: When using the snippet generator for the `sh`, `bat`, `powershell`, or `pwsh` steps, `label: ''` was unnecessarily added to the Groovy output even when no label was specified ([PR 121](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/121))
- Fix: Do not load builds from disk when computing the affinity key for the `node` step after a Jenkins restart ([JENKINS-60389](https://issues.jenkins-ci.org/browse/JENKINS-60389))
- Improvement: Allow messages printed to build logs when the `node` step is waiting on an agent to display rich text such as hyperlinks ([PR 134](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/134))
- Improvement: Update plugin description ([PR 100](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/100))
- Internal: **Update minimum required Jenkins version to 2.248**, update parent POM and update dependencies ([PR 127](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/127), [PR 137](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/137))
- Internal: Use new APIs from Jenkins core to autocomplete and validate labels for the `node` step in the snippet generator ([JENKINS-26097](https://issues.jenkins-ci.org/browse/JENKINS-26097))
- Internal: Replace usage of deprecated APIs and general code cleanup ([PR 133](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/133), [PR 140](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/140))
- Internal: Remove redundant security warnings from README ([PR 124](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/124))

## 2.35
2019 Nov 01

- Add `pwsh` step, which works exactly like the existing `powershell` step, but runs `pwsh.exe` instead of `powershell.exe`.
  For use with PowerShell Core 6.0 and newer. ([JENKINS-48803](https://issues.jenkins-ci.org/browse/JENKINS-48803))

## 2.34
2019 Sep 10

-   Fix: Prevent the `node` step from holding a strong reference to its
    body at runtime to avoid a memory leak. ([PR
    117](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/117))
-   Improvement: The fix
    for [JENKINS-41854](https://issues.jenkins-ci.org/browse/JENKINS-41854) in
    version 2.31 of this plugin resulted in confusing errors (a
    MissingContextVariableException) when running steps inside of
    a `node` step when the agent was disconnected. The error presented
    in these situations now indicates there is no connection to the
    specified agent, and when available, the reason that the agent is
    offline will be printed to the Pipeline's build log.
    ([JENKINS-58900](https://issues.jenkins-ci.org/browse/JENKINS-58900))
-   Internal: Update usages of deprecated APIs to their undeprecated
    equivalents, various cleanup and refactoring in test code. ([PR
    114](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/114))

## 2.33
2019 Jul 29

-   Fix: The final line of output from a shell step could be lost if it
    did not end with a newline in some cases ([PR
    112](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/112))
-   Internal: Update usages of deprecated APIs to their undeprecated
    equivalents ([PR
    110](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/110),
    [PR
    111](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/111))

## 2.32
2019 Jul 05

-   Fix: Abort the build immediately if an agent being used by the build
    is removed (deconfigured) from Jenkins
    ([JENKINS-49707](https://issues.jenkins-ci.org/browse/JENKINS-49707))

## 2.31
2019 Jun 03

> **WARNING**: You must update Pipeline Groovy Plugin to version 2.70 along with this
update.

-   Fix: Refresh references to files on agents when an agent reconnects.
    Fixes some cases where a step run on an agent would fail with
    a `RequestAbortedException` due to a `ChannelClosedException` even
    when the agent was connected to Jenkins if the agent had
    disconnected earlier during the build.
    ([JENKINS-41854](https://issues.jenkins-ci.org/browse/JENKINS-41854))

## 2.30
2019 Apr 03

-   Internal: Update parent POM so that the plugin can build with all
    tests passing on Java 11.

## 2.29
2019 Jan 31

> **NOTE**: Requires Jenkins 2.150.1 or newer

-   Enhancement: Change the affinity key used by Pipeline builds so that
    `node` blocks will use the same agent as in previous builds where
    possible when using the default Jenkins queue load balancer.
    ([JENKINS-36547](https://issues.jenkins-ci.org/browse/JENKINS-36547))
-   Fix: Add a system property
    named `org.jenkinsci.plugins.workflow.steps.durable_task.DurableTaskStep.REMOTE_TIMEOUT`
    which is the integer number of seconds for which `sh` and `bat`
    steps wait before timing out calls made to remote agents, such as
    those used to track the progress of the running script. The default
    value of the property has been increased from 10 seconds to 20
    seconds. Increasing this property is known to fix some cases of jobs
    failing spuriously with a `java.lang.InterruptedException`, but will
    also increase the amount of time before a failure is reported in the
    case of true failure rather than a remote agent responding
    slowly.  ([JENKINS-46507](https://issues.jenkins-ci.org/browse/JENKINS-46507))
-   Internal: Update dependencies to newer versions and fixing resulting
    test failures so that PCT runs are successful ([PR
    95](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/95),
    [PR
    97](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/97))
-   Internal: Do not enable watch mode automatically when running tests
    ([PR
    96](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/96))

## 2.28
2019 Jan 14

-   Enhancement: Add an optional `label` argument to the `sh`, `bat`,
    and `powershell` steps. If specified, the label will be used in the
    Blue Ocean and Pipeline Step views instead of the default labels
    (e.g. `Shell Script` or `Windows Batch Script`).
    ([JENKINS-55410](https://issues.jenkins-ci.org/browse/JENKINS-55410))
-   Improvement: Log additional information to the build log when
    a `node` step fails because the agent has been disconnected. ([PR
    94](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/94))

## 2.27
2018 Dec 14

-   Fix: Show descriptions for `sh` and `bat` steps in the Blue Ocean
    view when multiple arguments are passed to the step (e.g.,
    `returnStatus`, `returnStdout`). Previously, this only worked if the
    script was the only argument.
    ([JENKINS-52943](https://issues.jenkins-ci.org/browse/JENKINS-52943))

## 2.26
2018 Nov 01

-   Fix: Adjust log handling behavior in watch mode to work correctly
    with remotely buffered output
    ([JENKINS-54073](https://issues.jenkins-ci.org/browse/JENKINS-54073)). Watch
    mode is still disabled by default pending releases of the fixes
    for [JENKINS-54133](https://issues.jenkins-ci.org/browse/JENKINS-54133) and [JENKINS-54081](https://issues.jenkins-ci.org/browse/JENKINS-54081).
-   Improvement: Make Pipeline placeholder tasks expose the same data
    through the API as Freestyle builds.
    ([JENKINS-54356](https://issues.jenkins-ci.org/browse/JENKINS-54356))
    -   Thanks to community contributors **roguishmountain** and
        **scoheb** for this improvement!
-   Internal: Shut down thread pools when Jenkins shuts down. Should
    only affect other plugins using this plugin in their tests.

## 2.25
Oct 24, 2018

-   Fix: Disables
    the [JENKINS-52165](https://issues.jenkins-ci.org/browse/JENKINS-52165) changes
    in 2.22 pending fixes for issues introduced by those changes. Logs
    are now pulled from build agents by the Jenkins controller again instead
    of being pushed from build agents.
    ([JENKINS-54133](https://issues.jenkins-ci.org/browse/JENKINS-54133), [JENKINS-54081](https://issues.jenkins-ci.org/browse/JENKINS-54081),
    possibly
    [JENKINS-54073](https://issues.jenkins-ci.org/browse/JENKINS-54073)
    and [JENKINS-53888](https://issues.jenkins-ci.org/browse/JENKINS-53888))

## 2.24
Oct 22, 2018

-   Fix: Distinguish between abort (FlowInterruptedException) and
    failure (AbortException) for `sh` and `bat` steps. Notably, durable
    tasks with `returnStatus: true` that are manually aborted or time
    out will throw an exception where they previously would have
    succeeded. ([JENKINS-28822](https://issues.jenkins-ci.org/browse/JENKINS-28822))
-   Fix: Associate `node` steps with their build correctly after the
    Pipeline is restarted. In particular, this prevented custom
    implementations of QueueTaskDispatcher from handling restarted
    Pipeline builds correctly.
    ([JENKINS-53837](https://issues.jenkins-ci.org/browse/JENKINS-53837))
-   Improvement: Update documentation for the `node` step.

## 2.23
Oct 22, 2018

-   Released incorrectly. Use 2.24 instead.

## 2.22
Sep 25, 2018

-   Major Enhancement: Durable task logs are now pushed from build
    agents directly instead of being pulled from the build agent by the
    Jenkins controller. This reduces controller and network resource usage and
    will be required for external logging as described in
    [JEP-210](https://github.com/jenkinsci/jep/tree/master/jep/210).
    ([JENKINS-52165](https://issues.jenkins-ci.org/browse/JENKINS-52165))

## 2.21
Aug 22, 2018

-   Fix: Ensure that stopping `node` steps removes them from the queue
    regardless of the permissions of the user running the build.

## 2.20
Aug 7, 2018
> **IMPORTANT**: please also upgrade the [Pipeline Job Plugin](https://wiki.jenkins.io/display/JENKINS/Pipeline+Job+Plugin) to v2.24+ along with this upgrade, to avoid potential log encoding issues

-   [JEP-206](https://github.com/jenkinsci/jep/blob/master/jep/206/README.adoc) Use
    UTF-8 for all Pipeline build logs

## 2.19
Feb 16, 2018

-   Enhancement: Print message describing failure when a step fails with
    a negative exit code for a system failure
    ([JENKINS-48300](https://issues.jenkins-ci.org/browse/JENKINS-48300))
-   Bug Fix: Correctly show node name when failing a Pipeline that
    depends on a nonexistent executor (not just one that is offline)
-   Test fix: UTF-8 with Powershell step

## 2.18
Jan 22, 2018

-   [Fix security
    issue](https://jenkins.io/security/advisory/2018-01-22/)

## 2.17
Oct 13, 2017

-   [JENKINS-42264](https://issues.jenkins-ci.org/browse/JENKINS-42264) Feature:
    Add links to build nodes that pipelines are running on
-   [JENKINS-26148](https://issues.jenkins-ci.org/browse/JENKINS-26148) Use
    default implementation of StepExecution.stop
-   Fix: Use a dedicated Thread pool rather than Timer.get for
    DurableTask.Execution.check - avoid using up all Timer threads  ([PR
    \#53](https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/53))

## 2.16
Oct 13, 2017

-   [JENKINS-46738](https://issues.jenkins-ci.org/browse/JENKINS-46738) 
    Fix a deadlock that can occur with the PlaceHolderTasks used to
    claim an executor for a pipeline

## 2.15
Aug 30, 2017

-   Update to
    the [JENKINS-36013](http://36013@issue/) fix to
    ensure the timeout applies to resuming builds for ALL node types
    where the node doesn't exist
    -   Covers a few edge cases with EphemeralNodes

## 2.14
Aug 23, 2017

-   [JENKINS-36013](http://36013@issue) - Prevent
    Jenkins from spinning indefinitely trying to resume a build where
    the Agent is an EphemeralNode and will never come back
    -   Also covers cases where the node was removed by RetentionPolicy
        because it is destroyed, by aborting after a timeout (5 minutes
        by default)
        -   This ONLY happens if the Node is removed, not for simply
            disconnected nodes, and only is triggered upon restart of
            the controller
    -   Added System property
        'org.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.timeoutForNodeMillis'
        for how long to wait before aborting builds
-   [JENKINS-45553](http://45553@issue) - Fix a bug from
    use of Guice
    -   Optimize Action lookup when displaying executors used

## 2.13
July 25, 2017

-   JENKINS-26132 Display current stage on executor.
-   JENKINS-44981 Record information on queued task for reporting.

## 2.12
Jun 15, 2017

-   JENKINS-34581 Added a `powershell` step.
-   JENKINS-28182 Kill any spawned processes at the end of a `node`
    block.

## 2.11
Apr 25, 2017

-   Reduce log output to
    ameliorate [JENKINS-42048](https://issues.jenkins-ci.org/browse/JENKINS-42048).
-   Added logging for `ExecutorPickle`.

## 2.10
Mar 09, 2017

-   [JENKINS-42556](https://issues.jenkins-ci.org/browse/JENKINS-42556)
    Failure to resume builds inside `node` when `anonymous` was granted
    **Overall/Read** and **Job/Discover** but not **Job/Read** (a mode
    used to force login redirects from job URLs).
-   [JENKINS-34021](https://issues.jenkins-ci.org/browse/JENKINS-34021)
    Refinements to earlier fix, which under circumstances produced
    excessive noise in the log after forcible termination of a build
    inside `sh`/`bat`.

## 2.9
Feb 13, 2017

-   [JENKINS-41339](https://issues.jenkins-ci.org/browse/JENKINS-41339)
    Update of [Durable Task
    Plugin](https://wiki.jenkins.io/display/JENKINS/Durable+Task+Plugin)
    1.13 exposed a bug in a deprecated way of setting per-node
    environment variables.
-   Further robustness fix related to
    [JENKINS-34021](https://issues.jenkins-ci.org/browse/JENKINS-34021).
-   [JENKINS-41446](https://issues.jenkins-ci.org/browse/JENKINS-41446)
    Bind `$WORKSPACE` inside a `ws` block.

## 2.8
Jan 13, 2017

-   [JENKINS-40995](https://issues.jenkins-ci.org/browse/JENKINS-40995)
    Reported deadlock.
-   [JENKINS-41010](https://issues.jenkins-ci.org/browse/JENKINS-41010)
    API to associate a queue item with an entry in the flow graph.

## 2.7
Jan 10, 2017

-   [JENKINS-40909](https://issues.jenkins-ci.org/browse/JENKINS-40909)
    regression from 2.6 fixed. Only meaningful for updates directly from
    2.5-; if you have already updated to 2.6 it is too late.

## 2.6
Jan 05, 2016

> **WARNING**: Running builds created in 2.5- will not be loadable in this release
([JENKINS-40909](https://issues.jenkins-ci.org/browse/JENKINS-40909))

-   [JENKINS-40613](https://issues.jenkins-ci.org/browse/JENKINS-40613)
    Apply a stricter timeout to the `Timer` task used by `sh`/`bat`
    steps to check for new output.
-   [JENKINS-37730](https://issues.jenkins-ci.org/browse/JENKINS-37730)
    Include more diagnostics in the virtual thread dump for `sh`/`bat`
    steps.
-   [JENKINS-38769](https://issues.jenkins-ci.org/browse/JENKINS-38769)
    Make sure aborting a build inside a `sh`/`bat` step does something,
    even if the agent is unresponsive.
-   [JENKINS-37486](https://issues.jenkins-ci.org/browse/JENKINS-37486)
    `NullPointerException` thrown when aborting a build under unknown
    conditions.

## 2.5
Sep 23, 2016

-   [JENKINS-33511](https://issues.jenkins-ci.org/browse/JENKINS-33511)
    `WORKSPACE` and `NODE_LABELS` environment variables now available
    inside `node`, matching the behavior of freestyle projects.
-   [JENKINS-37121](https://issues.jenkins-ci.org/browse/JENKINS-37121)
    Unreproducible case of a build resumption hanging when it should
    have failed at once.
-   Unreproducible case of an executor slot remaining occupied but
    unkillable after the owning build was already aborted; can now be
    manually cleared.
-   Clearer display in log when a queue item is canceled while a build
    is trying to resume.

## 2.4
Jul 28, 2016

-   [JENKINS-26133](https://issues.jenkins-ci.org/browse/JENKINS-26133)
    Added `returnStdout` and `returnStatus` options to `sh` and `bat`.

## 2.3
Jun 29, 2016

-   [JENKINS-31842](https://issues.jenkins-ci.org/browse/JENKINS-31842)
    Provide information about `node` and `sh`/`bat` steps for use in the
    thread dump.
-   [JENKINS-26130](https://issues.jenkins-ci.org/browse/JENKINS-26130), [JENKINS-31842](https://issues.jenkins-ci.org/browse/JENKINS-31842)
    Provide information in the thread dump about pending `node` step
    resumption when a build is being restored from disk.

## 2.2
Jun 16, 2016

-   [JENKINS-26130](https://issues.jenkins-ci.org/browse/JENKINS-26130)
    When a build cannot be properly resumed because an agent it was
    running on (inside `node`) is no longer present, or offline, print
    periodic status messages to the build log rather than hanging
    silently. Also allow the build to be interrupted cleanly if the
    agent cannot be reattached.
-   [JENKINS-34021](https://issues.jenkins-ci.org/browse/JENKINS-34021)
    Work around and diagnostics for a `NullPointerException` when trying
    to abort a build inside a `sh`/`bat` step.

## 2.1
Jun 09, 2016

-   [JENKINS-34281](https://issues.jenkins-ci.org/browse/JENKINS-34281)
    workaround: if Jenkins denied anonymous read access, under some
    conditions shutting it down could result in loss of queue items,
    causing builds with `node` still waiting scheduling to hang after
    the restart.
-   If a queue item for a `node` block is deliberately cancelled, abort
    the build.
-   [JENKINS-34542](https://issues.jenkins-ci.org/browse/JENKINS-34542)
    Deadlock while interrupting a `node` step.
-   [JENKINS-28240](https://issues.jenkins-ci.org/browse/JENKINS-28240)
    `IllegalStateException` was thrown under some conditions.

## 2.0
Apr 05, 2016

-   First release under per-plugin versioning scheme. See [1.x
    changelog](https://github.com/jenkinsci/workflow-plugin/blob/82e7defa37c05c5f004f1ba01c93df61ea7868a5/CHANGES.md)
    for earlier releases.
-   Includes `node` and `ws` steps, and associated code, formerly in
    [Pipeline Supporting APIs
    Plugin](https://wiki.jenkins.io/display/JENKINS/Pipeline+Supporting+APIs+Plugin).
