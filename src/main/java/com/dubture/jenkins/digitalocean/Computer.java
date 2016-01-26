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

import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import hudson.slaves.AbstractCloudComputer;

import java.util.logging.Logger;

/**
 *
 * A {@link hudson.model.Computer} implementation for DigitalOcean. Holds a handle to an {@link Slave}.
 *
 * <p>Mainly responsible for updating the {@link Droplet} information via {@link Computer#updateInstanceDescription()}
 *
 * @author robert.gruendler@dubture.com
 */
public class Computer extends AbstractCloudComputer<Slave> {

    private static final Logger LOGGER = Logger.getLogger(Computer.class.getName());

    private final String authToken;

    private Integer dropletId;

    public Computer(Slave slave) {
        super(slave);
        dropletId = slave.getDropletId();
        authToken = slave.getCloud().getAuthToken();
    }

    public Droplet updateInstanceDescription() throws RequestUnsuccessfulException, DigitalOceanException {
        DigitalOceanClient apiClient = new DigitalOceanClient(authToken);
        return apiClient.getDropletInfo(dropletId);
    }

    @Override
    protected void onRemoved() {
        super.onRemoved();

        LOGGER.info("Slave removed, deleting droplet " + dropletId);
        DigitalOcean.tryDestroyDropletAsync(authToken, dropletId);
    }

    public Cloud getCloud() {
        return getNode().getCloud();
    }

    public int getSshPort() {
        return 22;
    }

    public String getRemoteAdmin() {
        return getNode().getRemoteAdmin();
    }
    public long getStartTimeMillis() {
        return getNode().getStartTimeMillis();
    }


}
