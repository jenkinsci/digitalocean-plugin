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

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 *
 * The {@link com.dubture.jenkins.digitalocean.Slave} is responsible for
 *
 * <ul>
 *   <li>Creating a DigitalOcean {@link com.dubture.jenkins.digitalocean.Computer}</li>
 *   <li>Destroying the {@link com.myjeeva.digitalocean.pojo.Droplet} if it's not needed anymore.</li>
 * </ul>
 *
 * @author robert.gruendler@dubture.com
 */
public class Slave extends AbstractCloudSlave {

    private static final Logger LOG = Logger.getLogger(Slave.class.getName());

    private final String cloudName;

    private final int idleTerminationTime;

    public final String initScript;

    private final Integer dropletId;

    private final String privateKey;

    private final String userData;

    private String remoteAdmin;

    public final String jvmOpts;

    private final long startTimeMillis;

    /**
     * {@link Slave}s are created by {@link SlaveTemplate}s
     */
    public Slave(String cloudName, String name, String nodeDescription, Integer dropletId, String privateKey,
                 String remoteFS, String remoteAdmin, int numExecutors, int idleTerminationTime, String userData,
                 Mode mode, String labelString, ComputerLauncher launcher, RetentionStrategy retentionStrategy,
                 List<? extends NodeProperty<?>> nodeProperties, String initScript, String jvmOpts)
            throws Descriptor.FormException, IOException {

        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);

        this.cloudName = cloudName;
        this.dropletId = dropletId;
        this.privateKey = privateKey;
        this.remoteAdmin = remoteAdmin;
        this.idleTerminationTime = idleTerminationTime;
        this.userData = userData;
        this.initScript = initScript;
        this.jvmOpts = jvmOpts;
        startTimeMillis = System.currentTimeMillis();
    }

    @Extension
    public static class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "DigitalOcean Slave";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    /**
     * Override to create a DigitalOcean {@link com.dubture.jenkins.digitalocean.Computer}
     * @return a new Computer instance, instantiated with this Slave instance.
     */
    @Override
    public Computer createComputer() {
        return new Computer(this);
    }

    /**
     * Retrieve a handle to the associated {@link com.dubture.jenkins.digitalocean.Cloud}
     * @return the Cloud associated with the specified cloudName
     */
    public Cloud getCloud() {
        return (Cloud) Jenkins.getInstance().getCloud(cloudName);
    }

    /**
     * Get the name of the remote admin user
     * @return the remote admin user, defaulting to "root"
     */
    public String getRemoteAdmin() {
        if (remoteAdmin == null || remoteAdmin.length() == 0)
            return "root";
        return remoteAdmin;
    }

    /**
     * Deletes the {@link com.myjeeva.digitalocean.pojo.Droplet} when not needed anymore.
     *
     * @param listener Unused
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        DigitalOcean.tryDestroyDropletAsync(getCloud().getAuthToken(), dropletId);
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public Integer getDropletId() {
        return dropletId;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public int getIdleTerminationTime() {
        return idleTerminationTime;
    }

    public String getUserData() {
        return userData;
    }

    public String getInitScript() {
        return initScript;
    }
}
