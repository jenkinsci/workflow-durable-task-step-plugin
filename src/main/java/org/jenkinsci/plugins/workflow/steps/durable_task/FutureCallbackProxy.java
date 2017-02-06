package org.jenkinsci.plugins.workflow.steps.durable_task;

import com.google.common.util.concurrent.FutureCallback;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link FutureCallback} that buffers the result and forwards
 * it to any number of {@link FutureCallback}s.
 * @author Kohsuke Kawaguchi
 */
final class FutureCallbackProxy<T> implements FutureCallback<T>, Serializable {
    private T result;
    private Throwable t;
    private boolean completed;

    private final List<FutureCallback<? super T>> callbacks = new ArrayList<>();

    @Override
    public void onSuccess(T result) {
        this.result = result;
        fire();
    }

    @Override
    public void onFailure(Throwable t) {
        this.t = t;
        fire();
    }

    private void fire() {
        List<FutureCallback<? super T>> clone;
        synchronized (this) {
            completed = true;
            clone = new ArrayList<>(callbacks);
        }
        for (FutureCallback<? super T> c : clone) {
            fire(c);
        }

    }

    private void fire(FutureCallback<? super T> c) {
        if (t!=null)
            c.onFailure(t);
        else
            c.onSuccess(result);
    }

    public void addCallback(FutureCallback<? super T> callback) {
        boolean fireNow = false;
        synchronized (this) {
            if (!completed)
                callbacks.add(callback);
            else
                fireNow = true;
        }

        if (fireNow)
            fire(callback);
    }

    private static final long serialVersionUID = 1L;
}
