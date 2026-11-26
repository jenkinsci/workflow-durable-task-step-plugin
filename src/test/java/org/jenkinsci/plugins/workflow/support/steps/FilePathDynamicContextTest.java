/*
 * The MIT License
 *
 * Copyright 2026 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.support.steps;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;
import hudson.slaves.DumbSlave;
import hudson.slaves.OfflineCause;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Focused tests of {@link FilePathDynamicContext#waitForReconnect}, independent of any pipeline,
 * so that the "does it come back in time" race is controlled rather than merely hoped for:
 * each test deterministically arranges the agent's state (online/offline/disconnecting/temporarily
 * offline) *before* calling the method, rather than racing a real reconnect against real step
 * execution. End-to-end coverage through a running pipeline lives in
 * {@link AgentErrorConditionTest#reconnectWithinWaitWindowAvoidsFailure}.
 */
@WithJenkins
class FilePathDynamicContextTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(FilePathDynamicContext.WAIT_FOR_RECONNECT_PROPERTY);
    }

    @Test
    void disabledByDefaultReturnsImmediately() throws Exception {
        long start = System.nanoTime();
        FilePath f = FilePathDynamicContext.waitForReconnect("no-such-node", "/tmp/x", null);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(f, nullValue());
        assertThat(elapsedMs, lessThan(2000L));
    }

    @Test
    void unknownNodeReturnsImmediatelyEvenWhenEnabled() throws Exception {
        System.setProperty(FilePathDynamicContext.WAIT_FOR_RECONNECT_PROPERTY, "10");
        long start = System.nanoTime();
        FilePath f = FilePathDynamicContext.waitForReconnect("no-such-node", "/tmp/x", null);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(f, nullValue());
        assertThat(elapsedMs, lessThan(2000L));
    }

    @Test
    void temporarilyOfflineAgentReturnsImmediately() throws Throwable {
        DumbSlave s = r.createOnlineSlave();
        s.toComputer().setTemporarilyOffline(true, new OfflineCause.UserCause(null, null));
        System.setProperty(FilePathDynamicContext.WAIT_FOR_RECONNECT_PROPERTY, "10");
        long start = System.nanoTime();
        FilePath f = FilePathDynamicContext.waitForReconnect(s.getNodeName(), s.getRootPath().getRemote(), null);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(f, nullValue());
        assertThat(elapsedMs, lessThan(2000L));
    }

    @Test
    void reconnectsBeforeTimeout() throws Throwable {
        DumbSlave s = r.createOnlineSlave();
        String node = s.getNodeName();
        String path = s.getRootPath().getRemote();
        dropChannel(s);
        System.setProperty(FilePathDynamicContext.WAIT_FOR_RECONNECT_PROPERTY, "15");
        var future = CompletableFuture.supplyAsync(() -> {
            try {
                return FilePathDynamicContext.waitForReconnect(node, path, TaskListener.NULL);
            } catch (InterruptedException x) {
                throw new RuntimeException(x);
            }
        });
        // Deliberately not synchronizing on the exact moment the poll loop is entered: unlike a
        // pipeline step, nothing here depends on winning a race against it. Reconnecting at any
        // point before the 15s window above elapses is picked up on the next 1s poll.
        Thread.sleep(2000);
        s.toComputer().connect(false).get();
        FilePath f = future.get(20, TimeUnit.SECONDS);
        assertThat(f, notNullValue());
    }

    @Test
    void givesUpAfterTimeout() throws Throwable {
        DumbSlave s = r.createOnlineSlave();
        String node = s.getNodeName();
        String path = s.getRootPath().getRemote();
        dropChannel(s);
        System.setProperty(FilePathDynamicContext.WAIT_FOR_RECONNECT_PROPERTY, "1");
        long start = System.nanoTime();
        FilePath f = FilePathDynamicContext.waitForReconnect(node, path, null);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(f, nullValue());
        assertThat(elapsedMs, greaterThanOrEqualTo(900L));
        assertThat(elapsedMs, lessThan(10_000L));
    }

    /**
     * Simulates a transient channel drop (e.g. a WebSocket agent blip) rather than an explicit
     * disconnect: closes the live {@link Channel} directly, which leaves {@link
     * hudson.model.Computer#getTerminatedBy} empty, unlike {@link hudson.model.Computer#disconnect}.
     */
    private static void dropChannel(DumbSlave s) throws Exception {
        VirtualChannel vc = s.toComputer().getChannel();
        assertThat(vc, notNullValue());
        ((Channel) vc).close();
        while (s.toComputer().isOnline()) {
            Thread.sleep(50);
        }
    }

}
