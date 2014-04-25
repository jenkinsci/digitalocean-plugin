/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 robert.gruendler@dubture.com
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

package com.dubture.jenkins.digitalocean;

import hudson.model.Descriptor;
import hudson.slaves.CloudSlaveRetentionStrategy;
import hudson.util.TimeUnit2;

/**
 *
 * The {@link com.dubture.jenkins.digitalocean.RetentionStrategy} is mainly used to determing
 * when an idle {@link com.myjeeva.digitalocean.pojo.Droplet} can be destroyed.
 *
 * @author robert.gruendler@dubture.com
 */
public class RetentionStrategy extends CloudSlaveRetentionStrategy<Computer> {

    public static class DescriptorImpl extends Descriptor<hudson.slaves.RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "DigitalOcean";
        }
    }

    public void start(Computer computer) {
        computer.connect(false);
    }

    @Override
    protected boolean isIdleForTooLong(Computer computer) {

        int idleTerminationTime = computer.getNode().getIdleTerminationTime();

        if (idleTerminationTime == 0) {
            return false;
        }

        return System.currentTimeMillis() - computer.getIdleStartMilliseconds() > TimeUnit2.MINUTES.toMillis(idleTerminationTime);
    }
}
