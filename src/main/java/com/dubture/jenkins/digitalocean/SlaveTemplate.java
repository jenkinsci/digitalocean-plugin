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

import com.myjeeva.digitalocean.exception.AccessDeniedException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.exception.ResourceNotFoundException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.DropletImage;
import com.myjeeva.digitalocean.pojo.DropletSize;
import com.myjeeva.digitalocean.pojo.Region;
import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProperty;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * A {@link com.dubture.jenkins.digitalocean.SlaveTemplate} represents the configuration values for creating a
 * new slave via a DigitalOcean droplet.
 *
 * Holds things like Image ID, size and region used for the specific droplet.
 *
 * The {@link SlaveTemplate#provision(com.myjeeva.digitalocean.impl.DigitalOceanClient, String, Integer, hudson.util.StreamTaskListener)} method
 * is the main entry point to create a new droplet via the DigitalOcean API when a new slave needs to be provisioned.
 *
 * @author robert.gruendler@dubture.com
 */
public class SlaveTemplate implements Describable<SlaveTemplate> {

    private static final String DROPLET_PREFIX = "jenkins-";

    private final String labelString;

    private final int idleTerminationInMinutes;

    public final String labels;

    /**
     * The Image to be used for the droplet.
     */
    public final Integer imageId;

    /**
     * The name for the droplet (generated automatically)
     */
    private final String dropletName;

    /**
     * The specified droplet size.
     */
    private final Integer sizeId;

    /**
     * The region for the droplet.
     */
    private final Integer regionId;

    private transient Set<LabelAtom> labelSet;

    protected transient Cloud parent;

    private static final Logger LOGGER = Logger.getLogger(SlaveTemplate.class.getName());


    /**
     * Data is injected from the global Jenkins configuration via jelly.
     * @param imageId
     * @param sizeId
     * @param regionId
     * @param idleTerminationInMinutes
     * @param labelString
     */
    @DataBoundConstructor
    public SlaveTemplate(String imageId, String sizeId, String regionId, String idleTerminationInMinutes, String labelString) {

        // prefix the dropletname with jenkins_ so we know its created by us and can be re-used as a slave if needed
        this.dropletName = DROPLET_PREFIX + UUID.randomUUID().toString();
        this.imageId = Integer.parseInt(imageId);
        this.sizeId = Integer.parseInt(sizeId);
        this.regionId = Integer.parseInt(regionId);

        this.idleTerminationInMinutes = Integer.parseInt(idleTerminationInMinutes);
        this.labelString = labelString;
        this.labels = Util.fixNull(labelString);

        readResolve();
    }

    protected Object readResolve() {
        labelSet = Label.parse(labels);
        return this;
    }

    /**
     * Creates a new droplet on DigitalOcean to be used as a Jenkins slave.
     *
     * @param apiClient
     * @param privateKey
     * @param sshKeyId
     *@param listener  @return
     * @throws IOException
     * @throws RequestUnsuccessfulException
     * @throws AccessDeniedException
     * @throws ResourceNotFoundException
     * @throws Descriptor.FormException
     */
    public Slave provision(DigitalOceanClient apiClient, String privateKey, Integer sshKeyId, StreamTaskListener listener) throws IOException, RequestUnsuccessfulException, AccessDeniedException, ResourceNotFoundException, Descriptor.FormException {

        PrintStream logger = listener.getLogger();
        try {
            logger.println("Starting to provision digital ocean droplet using image: " + imageId + ", region: " + regionId + ", size: " + sizeId);

            // check for existing droplets
            List<Droplet> availableDroplets = apiClient.getAvailableDroplets();
            for (Droplet existing : availableDroplets) {
                if (existing.getImageId().equals(imageId) && ! "archive".equals(existing.getStatus()) /*existing.isArchived()*/ && (existing.getName() != null && ! existing.getName().startsWith(DROPLET_PREFIX))) {
                    logger.println("Creating slave from existing droplet " + existing.getId());
                    return newSlave(existing, privateKey);
                }
            }

            // create a new droplet
            // TODO: set the data from the UI
            Droplet droplet = new Droplet();
            droplet.setName(dropletName);
            droplet.setSizeId(sizeId);
            droplet.setRegionId(regionId);
            droplet.setImageId(imageId);

            logger.println("Creating slave with new droplet " + dropletName);
            return newSlave(apiClient.createDroplet(droplet, sshKeyId.toString()), privateKey);
        } catch (Exception e) {
            e.printStackTrace(logger);
            throw new AssertionError();
        }
    }

    /**
     * Create a new {@link com.dubture.jenkins.digitalocean.Slave} from the given {@link com.myjeeva.digitalocean.pojo.Droplet}
     * @param droplet
     * @param privateKey
     * @return
     * @throws IOException
     * @throws Descriptor.FormException
     */
    private Slave newSlave(Droplet droplet, String privateKey) throws IOException, Descriptor.FormException {
        return new Slave(getParent().getName(), droplet.getName(), droplet.getId(), privateKey, "/jenkins", "root", 1, idleTerminationInMinutes, Node.Mode.NORMAL, labels, new ComputerLauncher(), new RetentionStrategy(), Collections.<NodeProperty<?>>emptyList(), "", "");
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {

        @Override
        public String getDisplayName() {
            return null;
        }

        public ListBoxModel doFillSizeIdItems(@RelativePath("..") @QueryParameter String apiKey, @RelativePath("..")  @QueryParameter String clientId) throws Exception {

            DigitalOceanClient client = new DigitalOceanClient(clientId, apiKey);
            List<DropletSize> availableSizes = client.getAvailableSizes();
            ListBoxModel model = new ListBoxModel();

            for (DropletSize size : availableSizes) {
                model.add(size.getName(), size.getId().toString());
            }

            return model;
        }

        public ListBoxModel doFillImageIdItems(@RelativePath("..") @QueryParameter String apiKey, @RelativePath("..")  @QueryParameter String clientId) throws Exception {

            DigitalOceanClient client = new DigitalOceanClient(clientId, apiKey);

            List<DropletImage> availableSizes = client.getAvailableImages();
            ListBoxModel model = new ListBoxModel();

            for (DropletImage image : availableSizes) {
                model.add(image.getName(), image.getId().toString());
            }

            return model;
        }

        public ListBoxModel doFillRegionIdItems(@RelativePath("..") @QueryParameter String apiKey, @RelativePath("..")  @QueryParameter String clientId) throws Exception {

            DigitalOceanClient client = new DigitalOceanClient(clientId, apiKey);

            List<Region> availableSizes = client.getAvailableRegions();
            ListBoxModel model = new ListBoxModel();

            for (Region image : availableSizes) {
                model.add(image.getName(), image.getId().toString());
            }

            return model;
        }

    }

    public Descriptor<SlaveTemplate> getDescriptor() {
        return Hudson.getInstance().getDescriptor(getClass());
    }

    public String getDropletName() {
        return dropletName;
    }

    public int getSizeId() {
        return sizeId;
    }

    public int getRegionId() {
        return regionId;
    }

    public String getLabels() {
        return labels;
    }

    public String getLabelString() {
        return labelString;
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    public Cloud getParent() {
        return parent;
    }

    public int getImageId() {
        return imageId;
    }

    public int getNumExecutors() {
        return 1;
    }

    public int getIdleTerminationInMinutes() {
        return idleTerminationInMinutes;
    }
}
