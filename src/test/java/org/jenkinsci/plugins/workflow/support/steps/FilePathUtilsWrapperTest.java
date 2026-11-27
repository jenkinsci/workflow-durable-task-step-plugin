package org.jenkinsci.plugins.workflow.support.steps;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;

import hudson.slaves.DumbSlave;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.FlagExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FilePathUtilsWrapperTest {

    @RegisterExtension
    private final FlagExtension<Duration> reconnectWait = new FlagExtension<>(() -> FilePathUtilsWrapper.RECONNECT_WAIT, wait -> FilePathUtilsWrapper.RECONNECT_WAIT = wait);

    @Test
    void findGivesUpWhenAgentDoesNotReconnectInTime(JenkinsRule r) throws Exception {
        var s = r.createOnlineSlave();
        dropChannel(s);
        FilePathUtilsWrapper.RECONNECT_WAIT = Duration.ofSeconds(1);
        long start = System.nanoTime();
        assertThat(FilePathUtilsWrapper.find(s.getNodeName(), s.getRemoteFS(), null), nullValue());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMs, greaterThanOrEqualTo(900L));
        assertThat(elapsedMs, lessThan(10_000L));
    }

    /** Unlike {@link hudson.model.Computer#disconnect}, records no termination request, as a network blip would not. */
    private static void dropChannel(DumbSlave s) throws Exception {
        s.toComputer().getChannel().close();
        s.toComputer().waitUntilOffline();
    }
}
