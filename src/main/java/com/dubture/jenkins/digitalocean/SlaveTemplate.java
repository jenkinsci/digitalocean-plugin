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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Strings;
import com.myjeeva.digitalocean.exception.DigitalOceanException;
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
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import static com.google.common.collect.Lists.newArrayList;

/**
 * A {@link SlaveTemplate} represents the configuration values for creating a new slave via a DigitalOcean droplet.
 *
 * <p>Holds things like Image ID, sizeId and region used for the specific droplet.
 *
 * <p>The {@link SlaveTemplate#provision(String, String, String, String, Integer)} method
 * is the main entry point to create a new droplet via the DigitalOcean API when a new slave needs to be provisioned.
 *
 * @author robert.gruendler@dubture.com
 */
@SuppressWarnings("unused")
public class SlaveTemplate implements Describable<SlaveTemplate> {

    private final String name;

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

    private final String username;

    private final String workspacePath;

    private final Integer instanceCap;

    /**
     * User-supplied data for configuring a droplet
     */
    private final String userData;

    /**
     * Setup script for preparing the new slave. Differs from userData in that Jenkins runs this script,
     * as opposed to the DigitalOcean provisioning process.
     */
    private final String initScript;

    private transient Set<LabelAtom> labelSet;

    private static final Logger LOGGER = Logger.getLogger(SlaveTemplate.class.getName());

    /**
     * Data is injected from the global Jenkins configuration via jelly.
     * @param imageId an image slug e.g. "debian-8-x64", or an integer e.g. of a backup, such as "12345678"
     * @param sizeId the image size e.g. "512mb" or "1gb"
     * @param regionId the region e.g. "nyc1"
     * @param idleTerminationInMinutes how long to wait before destroying a slave
     * @param numExecutors the number of executors that this slave supports
     * @param labelString the label for this slave
     * @param userData user data for DigitalOcean to apply when building the slave
     * @param initScript setup script to configure the slave
     */
    @DataBoundConstructor
    public SlaveTemplate(String name, String imageId, String sizeId, String regionId, String username, String workspacePath,
                         String idleTerminationInMinutes, String numExecutors, String labelString, String instanceCap,
                         String userData, String initScript) {

        LOGGER.log(Level.INFO, "Creating SlaveTemplate with imageId = {0}, sizeId = {1}, regionId = {2}",
                new Object[] { imageId, sizeId, regionId});

        this.name = name;
        this.imageId = imageId;
        this.sizeId = sizeId;
        this.regionId = regionId;
        this.username = username;
        this.workspacePath = workspacePath;

        this.idleTerminationInMinutes = tryParseInteger(idleTerminationInMinutes, 10);
        this.numExecutors = tryParseInteger(numExecutors, 1);
        this.labelString = labelString;
        this.labels = Util.fixNull(labelString);
        this.instanceCap = Integer.parseInt(instanceCap);

        this.userData = userData;
        this.initScript = initScript;

        readResolve();
    }

    public boolean isInstanceCapReached(String authToken, String cloudName) throws RequestUnsuccessfulException, DigitalOceanException {
        if (instanceCap == 0) {
            return false;
        }
        LOGGER.log(Level.INFO, "slave limit check");

        int count = 0;
        List<Node> nodes = Jenkins.getInstance().getNodes();
        for (Node n : nodes) {
            if (DropletName.isDropletInstanceOfSlave(n.getDisplayName(), cloudName, name)) {
                count++;
            }
        }

        if (count >= instanceCap) {
            return true;
        }

        count = 0;
        List<Droplet> availableDroplets = DigitalOcean.getDroplets(authToken);
        for (Droplet droplet : availableDroplets) {
            if ((droplet.isActive() || droplet.isNew())) {
                if (DropletName.isDropletInstanceOfSlave(droplet.getName(), cloudName, name)) {
                    count++;
                }
            }
        }

        return count >= instanceCap;
    }

    public Slave provision(String dropletName, String cloudName, String authToken, String privateKey, Integer sshKeyId)
            throws IOException, RequestUnsuccessfulException, Descriptor.FormException {

        LOGGER.log(Level.INFO, "Provisioning slave...");

        try {
            LOGGER.log(Level.INFO, "Starting to provision digital ocean droplet using image: " + imageId + " region: " + regionId + ", sizeId: " + sizeId);

            if (isInstanceCapReached(authToken, cloudName)) {
                throw new AssertionError();
            }

            // create a new droplet
            Droplet droplet = new Droplet();
            droplet.setName(dropletName);
            droplet.setSize(sizeId);
            droplet.setRegion(new Region(regionId));
            droplet.setImage(DigitalOcean.newImage(imageId));
            droplet.setKeys(newArrayList(new Key(sshKeyId)));

            if (!(userData == null || userData.trim().isEmpty())) {
                droplet.setUserData(userData);
            }

            LOGGER.log(Level.INFO, "Creating slave with new droplet " + dropletName);

            DigitalOceanClient apiClient = new DigitalOceanClient(authToken);
            Droplet createdDroplet = apiClient.createDroplet(droplet);

            return newSlave(cloudName, createdDroplet, privateKey);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
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
    private Slave newSlave(String cloudName, Droplet droplet, String privateKey) throws IOException, Descriptor.FormException {
        LOGGER.log(Level.INFO, "Creating new slave...");
        return new Slave(
                cloudName,
                droplet.getName(),
                "Computer running on DigitalOcean with name: " + droplet.getName(),
                droplet.getId(),
                privateKey,
                workspacePath,
                username,
                numExecutors,
                idleTerminationInMinutes,
                userData,
                Node.Mode.NORMAL,
                labels,
                new ComputerLauncher(),
                new RetentionStrategy(),
                Collections.<NodeProperty<?>>emptyList(),
                Util.fixNull(initScript),
                ""
        );
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {

        @Override
        public String getDisplayName() {
            return null;
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (Strings.isNullOrEmpty(name)) {
                return FormValidation.error("Must be set");
            } else if (!DropletName.isValidSlaveName(name)) {
                return FormValidation.error("Must consist of A-Z, a-z, 0-9 and . symbols");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckUsername(@QueryParameter String username) {
            if (Strings.isNullOrEmpty(username)) {
                return FormValidation.error("Must be set");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckWorkspacePath(@QueryParameter String workspacePath) {
            if (Strings.isNullOrEmpty(workspacePath)) {
                return FormValidation.error("Must be set");
            } else {
                return FormValidation.ok();
            }
        }

        private static FormValidation doCheckNonNegativeNumber(String stringNumber) {
            if (Strings.isNullOrEmpty(stringNumber)) {
                return FormValidation.error("Must be set");
            } else {
                int number;

                try {
                    number = Integer.parseInt(stringNumber);
                } catch (Exception e) {
                    return FormValidation.error("Must be a number");
                }

                if (number < 0) {
                    return FormValidation.error("Must be a nonnegative number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckNumExecutors(@QueryParameter String numExecutors) {
            if (Strings.isNullOrEmpty(numExecutors)) {
                return FormValidation.error("Must be set");
            } else {
                int number;

                try {
                    number = Integer.parseInt(numExecutors);
                } catch (Exception e) {
                    return FormValidation.error("Must be a number");
                }

                if (number <= 0) {
                    return FormValidation.error("Must be a positive number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckIdleTerminationInMinutes(@QueryParameter String idleTerminationInMinutes) {
            if (Strings.isNullOrEmpty(idleTerminationInMinutes)) {
                return FormValidation.error("Must be set");
            } else {
                int number;

                try {
                    number = Integer.parseInt(idleTerminationInMinutes);
                } catch (Exception e) {
                    return FormValidation.error("Must be a number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String instanceCap) {
            return doCheckNonNegativeNumber(instanceCap);
        }

        public FormValidation doCheckSizeId(@RelativePath("..") @QueryParameter String authToken) {
            return Cloud.DescriptorImpl.doCheckAuthToken(authToken);
        }

        public FormValidation doCheckImageId(@RelativePath("..") @QueryParameter String authToken) {
            return Cloud.DescriptorImpl.doCheckAuthToken(authToken);
        }

        public FormValidation doCheckRegionId(@RelativePath("..") @QueryParameter String authToken) {
            return Cloud.DescriptorImpl.doCheckAuthToken(authToken);
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

            SortedMap<String, Image> availableImages = DigitalOcean.getAvailableImages(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Map.Entry<String, Image> entry : availableImages.entrySet()) {
                final Image image = entry.getValue();

                // For non-snapshots, use the image ID instead of the slug (which isn't available anyway)
                // so that we can build images based upon backups.
                final String value = DigitalOcean.getImageIdentifier(image);

                model.add(entry.getKey(), value);
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

    public String getName() {
        return name;
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

    public String getImageId() {
        return imageId;
    }

    public String getUsername() {
        return username;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    public int getIdleTerminationInMinutes() {
        return idleTerminationInMinutes;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public String getUserData() {
        return userData;
    }

    public String getInitScript() {
        return initScript;
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
