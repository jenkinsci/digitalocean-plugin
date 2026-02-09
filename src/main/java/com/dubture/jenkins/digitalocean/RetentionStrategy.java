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
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Descriptor;
import hudson.slaves.CloudSlaveRetentionStrategy;
import hudson.slaves.OfflineCause;

import java.util.concurrent.TimeUnit;

/**
 *
 * The {@link RetentionStrategy} is mainly used to determine
 * when an idle {@link Droplet} can be destroyed.
 *
 * @author robert.gruendler@dubture.com
 */
public class RetentionStrategy extends CloudSlaveRetentionStrategy<DigitalOceanComputer> {

    private final int idleTerminationTime;
    private final boolean oneShot;

    public RetentionStrategy(int idleTerminationTime, boolean oneShot) {
        this.idleTerminationTime = idleTerminationTime;
        this.oneShot = oneShot;
    }

    private static class DescriptorImpl extends Descriptor<hudson.slaves.RetentionStrategy<?>> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "DigitalOcean";
        }
    }

    public void start(DigitalOceanComputer digitalOceanComputer) {
        digitalOceanComputer.connect(false);
    }

    @Override
    protected long checkCycle() {
        // For -1 (ASAP termination) or one-shot mode, check every 6 seconds; otherwise every 60 seconds
        return (idleTerminationTime < 0 || oneShot) ? 6L : 60L;
    }

    @Override
    protected boolean isIdleForTooLong(DigitalOceanComputer digitalOceanComputer) {
        Slave node = digitalOceanComputer.getNode();

        if(node == null) {
            return false;
        }

        int idleTerminationTime = node.getIdleTerminationTime();

        if (node.isOneShot()) {
            // One-shot mode: terminate immediately after running first job
            // Optimal for per-minute billing - each droplet runs exactly one job then terminates

            // Immediately take the agent offline to prevent new jobs from being assigned
            // This ensures no race condition where a second job gets assigned during termination
            if (!digitalOceanComputer.isTemporarilyOffline()) {
                digitalOceanComputer.setTemporarilyOffline(true,
                    OfflineCause.create(Messages.__OneShot_OfflineCause()));
            }

            // Terminate immediately when idle (no waiting period)
            return digitalOceanComputer.isIdle();
        } else if (idleTerminationTime == 0) {
            return false;
        } else if (idleTerminationTime > 0) {
            return System.currentTimeMillis() - digitalOceanComputer.getIdleStartMilliseconds() > TimeUnit.MINUTES.toMillis(idleTerminationTime);
        } else {
            // Negative values (e.g., -1) terminate immediately when idle for ASAP termination
            return digitalOceanComputer.isIdle();
        }
    }
}
