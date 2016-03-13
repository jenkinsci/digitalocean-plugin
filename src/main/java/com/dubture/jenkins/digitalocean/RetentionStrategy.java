/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 robert.gruendler@dubture.com
 *               2016 Maxim Biro <nurupo.contributions@gmail.com>
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

import com.myjeeva.digitalocean.pojo.Droplet;
import hudson.model.Descriptor;
import hudson.slaves.CloudSlaveRetentionStrategy;
import hudson.util.TimeUnit2;

/**
 *
 * The {@link RetentionStrategy} is mainly used to determine
 * when an idle {@link Droplet} can be destroyed.
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
    protected long checkCycle() {
        return 1; // ask Jenkins to check every 1 minute, though it might decide to check in 2 or 3 (or longer?)
    }

    @Override
    protected boolean isIdleForTooLong(Computer computer) {
        int idleTerminationTime = computer.getNode().getIdleTerminationTime();

        if (idleTerminationTime == 0) {
            return false;
        }

        if (idleTerminationTime > 0) {
            return System.currentTimeMillis() - computer.getIdleStartMilliseconds() > TimeUnit2.MINUTES.toMillis(idleTerminationTime);
        } else if (idleTerminationTime < 0 && computer.isIdle()) {
            // DigitalOcaen charges for the next hour at 1:30, 2:30, 3:30, etc. up time, so kill the node
            // if it idles and is about to get charged for next hour
            long uptimeMinutes = TimeUnit2.MILLISECONDS.toMinutes(System.currentTimeMillis() - computer.getStartTimeMillis());

            if (uptimeMinutes < 60) {
                return false;
            }

            while (uptimeMinutes >= 60) {
                uptimeMinutes -= 60;
            }

            return uptimeMinutes >= 25 && uptimeMinutes < 30;
        }

        return false;
    }
}
