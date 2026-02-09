/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 robert.gruendler@dubture.com
 *               2016 Maxim Biro <nurupo.contributions@gmail.com>
 *               2017 Harald Sitter <sitter@kde.org>
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
import java.util.Arrays;
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

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;

/**
 * A {@link SlaveTemplate} represents the configuration values for creating a new agent via a DigitalOcean droplet.
 *
 * <p>Holds things like Image ID, sizeId and region used for the specific droplet.
 *
 * <p>The {@link SlaveTemplate#provision} method
 * is the main entry point to create a new droplet via the DigitalOcean API when a new agent needs to be provisioned.
 *
 * @author robert.gruendler@dubture.com
 */
public class SlaveTemplate implements Describable<SlaveTemplate> {

    private final String name;

    private final String labelString;

    private final int idleTerminationInMinutes;

    /**
     * The maximum number of executors that this agent will run.
     */
    private final int numExecutors;

    private final String labels;

    private final Boolean labellessJobsAllowed;

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

    private final Integer sshPort;

    private final Boolean setupPrivateNetworking;

    private final Integer instanceCap;

    private final Boolean installMonitoringAgent;

    private final String tags;

    /**
     * User-supplied data for configuring a droplet
     */
    private final String userData;

    /**
     * Setup script for preparing the new agent. Differs from userData in that Jenkins runs this script,
     * as opposed to the DigitalOcean provisioning process.
     */
    private final String initScript;

    private final boolean oneShot;

    private transient Set<LabelAtom> labelSet;

    private static final Logger LOGGER = Logger.getLogger(SlaveTemplate.class.getName());

    /**
     * Data is injected from the global Jenkins configuration via jelly.
     * @param name image name
     * @param imageId an image slug e.g. "debian-8-x64", or an integer e.g. of a backup, such as "12345678"
     * @param sizeId the image size e.g. "512mb" or "1gb"
     * @param regionId the region e.g. "nyc1"
     * @param username username to login
     * @param workspacePath path to the workspace
     * @param sshPort ssh port to be used
     * @param idleTerminationInMinutes how long to wait before destroying a agent
     * @param numExecutors the number of executors that this agent supports
     * @param labelString the label for this agent
     * @param labellessJobsAllowed if jobs without a label are allowed
     * @param instanceCap if the number of created instances is capped
     * @param installMonitoring whether expanded monitoring tool agent should be installed
     * @param tags the droplet tags
     * @param userData user data for DigitalOcean to apply when building the agent
     * @param initScript setup script to configure the agent
     */
    @DataBoundConstructor
    public SlaveTemplate(String name, String imageId, String sizeId, String regionId, String username, String workspacePath,
                         Integer sshPort, Boolean setupPrivateNetworking, String idleTerminationInMinutes, String numExecutors, String labelString,
                         Boolean labellessJobsAllowed, String instanceCap, Boolean installMonitoring, String tags,
                         String userData, String initScript, Boolean oneShot) {

        LOGGER.log(Level.INFO, "Creating SlaveTemplate with imageId = {0}, sizeId = {1}, regionId = {2}",
                new Object[] { imageId, sizeId, regionId});

        this.name = name;
        this.imageId = imageId;
        this.sizeId = sizeId;
        this.regionId = regionId;
        this.username = username;
        this.workspacePath = workspacePath;
        this.sshPort = sshPort;
        if (setupPrivateNetworking == null) {
            LOGGER.log(Level.WARNING, "Private networking configuration not set for Slavetemplate with imageid = {0}", new Object[]{imageId});
            this.setupPrivateNetworking = false;
        } else {
            this.setupPrivateNetworking = setupPrivateNetworking;
        }
        this.idleTerminationInMinutes = tryParseInteger(idleTerminationInMinutes, 10);
        this.numExecutors = tryParseInteger(numExecutors, 1);
        this.labelString = labelString;
        this.labellessJobsAllowed = labellessJobsAllowed;
        this.labels = Util.fixNull(labelString);
        this.instanceCap = Integer.parseInt(instanceCap);
        this.installMonitoringAgent = installMonitoring;
        this.tags = tags;

        this.userData = userData;
        this.initScript = initScript;
        this.oneShot = oneShot != null ? oneShot : false;

        readResolve();
    }


    public boolean isInstanceCapReachedLocal(String cloudName) {
        if (instanceCap == 0) {
            return false;
        }
        LOGGER.log(Level.INFO, "agent limit check");

        int count = 0;
        List<Node> nodes = Jenkins.get().getNodes();
        for (Node n : nodes) {
            if (DropletName.isDropletInstanceOfSlave(n.getDisplayName(), cloudName, name)) {
                count++;
            }
        }

        return count >= instanceCap;
    }

    public boolean isInstanceCapReachedRemote(List<Droplet> droplets, String cloudName) throws DigitalOceanException {
        LOGGER.log(Level.INFO, "agent limit check");
        int count = 0;
        for (Droplet droplet : droplets) {
            if ((droplet.isActive() || droplet.isNew())) {
                if (DropletName.isDropletInstanceOfSlave(droplet.getName(), cloudName, name)) {
                    count++;
                }
            }
        }

        return count >= instanceCap;
    }

    public Slave provision(ProvisioningActivity.Id provisioningId,
                           String dropletName,
                           String cloudName,
                           String authToken,
                           String privateKey,
                           Integer sshKeyId,
                           List<Droplet> droplets,
                           Boolean usePrivateNetworking)
            throws IOException, RequestUnsuccessfulException, Descriptor.FormException {

        LOGGER.log(Level.INFO, "Provisioning agent...");

        try {
            LOGGER.log(Level.INFO, "Starting to provision digital ocean droplet using image: {0}, sizeId = {1}, regionId = {2}",
                    new Object[]{imageId, sizeId, regionId});

            if (isInstanceCapReachedLocal(cloudName) || isInstanceCapReachedRemote(droplets, cloudName)) {
                String msg = String.format("instance cap reached for %s in %s", dropletName, cloudName);
                LOGGER.log(Level.INFO, msg);
                throw new AssertionError(msg);
            }

            if (usePrivateNetworking == null) {
                LOGGER.log(Level.WARNING, "Private networking usage not set for Slavetemplate with imageid = {0}", new Object[]{imageId});
                usePrivateNetworking = false;
            }

            // create a new droplet
            Droplet droplet = new Droplet();
            droplet.setName(dropletName);
            droplet.setSize(sizeId);
            droplet.setRegion(new Region(regionId));
            droplet.setImage(DigitalOcean.newImage(imageId));
            droplet.setKeys(Collections.singletonList(new Key(sshKeyId)));
            droplet.setInstallMonitoring(installMonitoringAgent);
            droplet.setEnablePrivateNetworking(
                    (usePrivateNetworking == null ? false : usePrivateNetworking) || (setupPrivateNetworking == null ? false : setupPrivateNetworking)
            );
            droplet.setTags(Arrays.asList(Util.tokenize(Util.fixNull(tags))));

            if (!(userData == null || userData.trim().isEmpty())) {
                droplet.setUserData(userData);
            }

            LOGGER.log(Level.INFO, "Creating agent with new droplet " + dropletName);

            DigitalOceanClient apiClient = new DigitalOceanClient(authToken);
            Droplet createdDroplet = apiClient.createDroplet(droplet);

            return newSlave(provisioningId, cloudName, createdDroplet, privateKey);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            String msg = String.format("Unexpected error raised during provisioning of %s:%n%s", dropletName, e.getMessage());
            LOGGER.log(Level.WARNING,  msg, e);
            throw new AssertionError(msg);
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
    private Slave newSlave(ProvisioningActivity.Id provisioningId, String cloudName, Droplet droplet, String privateKey) throws IOException, Descriptor.FormException {
        LOGGER.log(Level.INFO, "Creating new agent...");
        return new Slave(
                provisioningId,
                cloudName,
                droplet.getName(),
                "DigitalOceanComputer running on DigitalOcean with name: " + droplet.getName(),
                droplet.getId(),
                privateKey,
                username,
                workspacePath,
                sshPort,
                numExecutors,
                idleTerminationInMinutes,
                labels,
                new DigitalOceanComputerLauncher(),
                new RetentionStrategy(idleTerminationInMinutes, oneShot),
                Collections.emptyList(),
                Util.fixNull(initScript),
                oneShot
        );
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "DigitalOcean Agent Template";
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
                    return FormValidation.error("Must be a non-negative number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckSshPort(@QueryParameter String sshPort) {
            return doCheckNonNegativeNumber(sshPort);
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
                try {
                    //noinspection ResultOfMethodCallIgnored
                    Integer.parseInt(idleTerminationInMinutes);
                } catch (Exception e) {
                    return FormValidation.error("Must be a number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String instanceCap) {
            return doCheckNonNegativeNumber(instanceCap);
        }

        public FormValidation doCheckSizeId(@RelativePath("..") @QueryParameter String authTokenCredentialId) {
            String authToken = DigitalOceanCloud.getAuthTokenFromCredentialId(authTokenCredentialId);
            return DigitalOceanCloud.DescriptorImpl.doCheckAuthToken(authToken);
        }

        public FormValidation doCheckImageId(@RelativePath("..") @QueryParameter String authTokenCredentialId) {
            String authToken = DigitalOceanCloud.getAuthTokenFromCredentialId(authTokenCredentialId);
            return DigitalOceanCloud.DescriptorImpl.doCheckAuthToken(authToken);
        }

        public FormValidation doCheckRegionId(@RelativePath("..") @QueryParameter String authTokenCredentialId) {
            String authToken = DigitalOceanCloud.getAuthTokenFromCredentialId(authTokenCredentialId);
            return DigitalOceanCloud.DescriptorImpl.doCheckAuthToken(authToken);
        }

        public ListBoxModel doFillSizeIdItems(@RelativePath("..") @QueryParameter String authTokenCredentialId) throws Exception {
            ListBoxModel model = new ListBoxModel();
            String authToken = DigitalOceanCloud.getAuthTokenFromCredentialId(authTokenCredentialId);
            if (StringUtils.isBlank(authToken)) {
              return model;
            }

            List<Size> availableSizes = DigitalOcean.getAvailableSizes(authToken);

            for (Size size : availableSizes) {
                model.add(DigitalOcean.buildSizeLabel(size), size.getSlug());
            }

            return model;
        }

        public ListBoxModel doFillImageIdItems(@RelativePath("..") @QueryParameter String authTokenCredentialId) throws Exception {

            ListBoxModel model = new ListBoxModel();
            String authToken = DigitalOceanCloud.getAuthTokenFromCredentialId(authTokenCredentialId);
            if (StringUtils.isBlank(authToken)) {
              return model;
            }

            SortedMap<String, Image> availableImages = DigitalOcean.getAvailableImages(DigitalOceanCloud.getAuthTokenFromCredentialId(authTokenCredentialId));

            for (Map.Entry<String, Image> entry : availableImages.entrySet()) {
                final Image image = entry.getValue();

                // For non-snapshots, use the image ID instead of the slug (which isn't available anyway)
                // so that we can build images based upon backups.
                final String value = DigitalOcean.getImageIdentifier(image);

                model.add(entry.getKey(), value);
            }

            return model;
        }

        public ListBoxModel doFillRegionIdItems(@RelativePath("..") @QueryParameter String authTokenCredentialId) throws Exception {
            ListBoxModel model = new ListBoxModel();
            String authToken = DigitalOceanCloud.getAuthTokenFromCredentialId(authTokenCredentialId);
            if (StringUtils.isBlank(authToken)) {
              return model;
            }

            List<Region> availableSizes = DigitalOcean.getAvailableRegions(DigitalOceanCloud.getAuthTokenFromCredentialId(authTokenCredentialId));

            for (Region region : availableSizes) {
                model.add(region.getName(), region.getSlug());
            }

            return model;
        }
    }

    @SuppressWarnings("unchecked")
    public Descriptor<SlaveTemplate> getDescriptor() {
        return Jenkins.get().getDescriptor(getClass());
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

    public boolean isLabellessJobsAllowed() {
        return labellessJobsAllowed;
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

    public boolean isInstallMonitoring() {
        return installMonitoringAgent;
    }

    public String getTags() {
        return tags;
    }

    public String getUserData() {
        return userData;
    }

    public String getInitScript() {
        return initScript;
    }

    public boolean isOneShot() {
        return oneShot;
    }

    public int getSshPort() {
        return sshPort;
    }

    public boolean isSetupPrivateNetworking() { return setupPrivateNetworking; }

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
