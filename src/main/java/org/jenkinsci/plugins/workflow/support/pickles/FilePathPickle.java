/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.pickles;

import com.google.common.util.concurrent.ListenableFuture;
import hudson.Extension;
import hudson.FilePath;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.pickles.Pickle;

/**
 * @author Kohsuke Kawaguchi
 */
public class FilePathPickle extends Pickle {

    private final String slave;
    private final String path;

    private FilePathPickle(FilePath v) {
        slave = FilePathUtils.getNodeName(v);
        path = v.getRemote();
    }

    @Override
    public ListenableFuture<FilePath> rehydrate(FlowExecutionOwner owner) {
        return new TryRepeatedly<FilePath>(1) {
            @Override
            protected FilePath tryResolve() {
                return FilePathUtils.find(slave, path);
            }
            @Override protected FlowExecutionOwner getOwner() {
                return owner;
            }
            @Override public String toString() {
                return "Looking for path named ‘" + path + "’ on computer named ‘" + slave + "’";
            }
        };
    }

    @Override public String toString() {
        return "FilePathPickle{" + "slave=" + slave + ", path=" + path + '}';
    }

    @Extension public static final class Factory extends SingleTypedPickleFactory<FilePath> {
        @Override protected Pickle pickle(FilePath object) {
            return new FilePathPickle(object);
        }
    }

}
