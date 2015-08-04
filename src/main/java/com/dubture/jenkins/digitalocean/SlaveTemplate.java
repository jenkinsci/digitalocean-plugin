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

import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.Image;
import com.myjeeva.digitalocean.pojo.Key;
import com.myjeeva.digitalocean.pojo.Region;
import com.myjeeva.digitalocean.pojo.Size;
import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProperty;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.collect.Lists.newArrayList;

/**
 * A {@link SlaveTemplate} represents the configuration values for creating a new slave via a DigitalOcean droplet.
 *
 * <p>Holds things like Image ID, sizeId and region used for the specific droplet.
 *
 * <p>The {@link SlaveTemplate#provision(DigitalOceanClient, String, String, Integer, StreamTaskListener)} method
 * is the main entry point to create a new droplet via the DigitalOcean API when a new slave needs to be provisioned.
 *
 * @author robert.gruendler@dubture.com
 */
@SuppressWarnings("unused")
public class SlaveTemplate implements Describable<SlaveTemplate> {

    private static final String DROPLET_PREFIX = "jenkins-";

    private final String labelString;

    private final int idleTerminationInMinutes;

    /**
     * The maximum number of executors that this slave will run.
     */
    private final int numExecutors;

    private final String labels;

    /**
     * The Image to be used for the droplet.
     */
    private final String imageId;

    /**
     * The specified droplet sizeId.
     */
    private final String sizeId;

    /**
     * The region for the droplet.
     */
    private final String regionId;

    /**
     * User-supplied data for configuring a droplet
     */
    private final String userData;

    private transient Set<LabelAtom> labelSet;

    protected transient Cloud parent;

    private static final Logger LOGGER = Logger.getLogger(SlaveTemplate.class.getName());


    /**
     * Data is injected from the global Jenkins configuration via jelly.
     * @param imageId an image id e.g. "12345678"
     * @param sizeId the image size e.g. "512mb" or "1gb"
     * @param regionId the region e.g. "nyc1"
     * @param idleTerminationInMinutes how long to wait before destroying a slave
     * @param numExecutors the number of executors that this slave supports
     * @param labelString the label for this slave
     * @param userData user data for DigitalOcean to apply when building the slave
     */
    @DataBoundConstructor
    public SlaveTemplate(String imageId, String sizeId, String regionId, String idleTerminationInMinutes,
            String numExecutors, String labelString, String userData) {

        LOGGER.log(Level.INFO, "Creating SlaveTemplate with imageId = {0}, sizeId = {1}, regionId = {2}",
            new Object[] { imageId, sizeId, regionId});

        this.imageId = imageId;
        this.sizeId = sizeId;
        this.regionId = regionId;

        this.idleTerminationInMinutes = tryParseInteger(idleTerminationInMinutes, 10);
        this.numExecutors = tryParseInteger(numExecutors, 1);
        this.labelString = labelString;
        this.labels = Util.fixNull(labelString);

        this.userData = userData;

        readResolve();
    }

    /**
     * Creates a new droplet on DigitalOcean to be used as a Jenkins slave.
     *
     * @param apiClient the v2 API client to use
     * @param privateKey the RSA private key to use
     * @param sshKeyId the SSH key name name to use
     * @param listener the listener on which to report progress
     * @return the provisioned {@link Slave}
     * @throws IOException
     * @throws RequestUnsuccessfulException
     * @throws Descriptor.FormException
     */
    public Slave provision(DigitalOceanClient apiClient, String dropletName, String privateKey, Integer sshKeyId, StreamTaskListener listener)
            throws IOException, RequestUnsuccessfulException, Descriptor.FormException {

        LOGGER.log(Level.INFO, "Provisioning slave...");

        PrintStream logger = listener.getLogger();
        try {
            logger.printf("Starting to provision digital ocean droplet using image: %s, region: %s, sizeId: %s%n",
                imageId, regionId, sizeId);

            // create a new droplet
            Droplet droplet = new Droplet();
            droplet.setName(dropletName);
            droplet.setSize(sizeId);
            droplet.setRegion(new Region(regionId));
            droplet.setImage(new Image(Integer.parseInt(imageId)));
            droplet.setKeys(newArrayList(new Key(sshKeyId)));

            if (!(userData == null || userData.isEmpty())) {
                droplet.setUserData(userData);
            }

            logger.println("Creating slave with new droplet " + dropletName);

            Droplet createdDroplet = apiClient.createDroplet(droplet);
            return newSlave(createdDroplet, privateKey);
        } catch (Exception e) {
            e.printStackTrace(logger);
            throw new AssertionError();
        }
    }

    /**
     * Create a new {@link Slave} from the given {@link Droplet}
     * @param droplet the droplet being created
     * @param privateKey the RSA private key being used
     * @return the provisioned {@link Slave}
     * @throws IOException
     * @throws Descriptor.FormException
     */
    private Slave newSlave(Droplet droplet, String privateKey) throws IOException, Descriptor.FormException {
        LOGGER.log(Level.INFO, "Creating new slave...");
        return new Slave(
                getParent().getName(),
                droplet.getName(),
                "Computer running on DigitalOcean with name: " + droplet.getName(),
                droplet.getId(),
                privateKey,
                "/jenkins",
                "root",
                numExecutors,
                idleTerminationInMinutes,
                userData,
                Node.Mode.NORMAL,
                labels,
                new ComputerLauncher(),
                new RetentionStrategy(),
                Collections.<NodeProperty<?>>emptyList(),
                "",
                ""
        );
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {

        @Override
        public String getDisplayName() {
            return null;
        }

        public ListBoxModel doFillSizeIdItems(@RelativePath("..") @QueryParameter String authToken) throws Exception {

            List<Size> availableSizes = DigitalOcean.getAvailableSizes(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Size size : availableSizes) {
                model.add(DigitalOcean.buildSizeLabel(size), size.getSlug());
            }

            return model;
        }

        public ListBoxModel doFillImageIdItems(@RelativePath("..") @QueryParameter String authToken) throws Exception {

            SortedMap<String, Image> availableSizes = DigitalOcean.getAvailableImages(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Map.Entry<String, Image> entry : availableSizes.entrySet()) {
                // Reference image IDs instead of slugs so that we can build
                // images based upon snapshots as well as standard images
                model.add(entry.getKey(), entry.getValue().getId().toString());
            }

            return model;
        }

        public ListBoxModel doFillRegionIdItems(@RelativePath("..") @QueryParameter String authToken) throws Exception {

            List<Region> availableSizes = DigitalOcean.getAvailableRegions(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Region region : availableSizes) {
                model.add(region.getName(), region.getSlug());
            }

            return model;
        }
    }

    @SuppressWarnings("unchecked")
    public Descriptor<SlaveTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public String createDropletName() {
        return DROPLET_PREFIX + UUID.randomUUID().toString();
    }

    public String getSizeId() {
        return sizeId;
    }

    public String getRegionId() {
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

    public String getImageId() {
        return imageId;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    public int getIdleTerminationInMinutes() {
        return idleTerminationInMinutes;
    }

    public String getUserData() {
        return userData;
    }

    private static int tryParseInteger(final String integerString, final int defaultValue) {
        try {
            return Integer.parseInt(integerString);
        }
        catch (NumberFormatException e) {
            LOGGER.log(Level.INFO, "Invalid integer {0}, defaulting to {1}", new Object[] {integerString, defaultValue});
            return defaultValue;
        }
    }

    protected Object readResolve() {
        labelSet = Label.parse(labels);
        return this;
    }
}
