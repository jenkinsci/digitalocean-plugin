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

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.Droplets;
import com.myjeeva.digitalocean.pojo.Key;
import com.myjeeva.digitalocean.pojo.Keys;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.collect.Lists.newArrayList;

/**
 *
 * The {@link com.dubture.jenkins.digitalocean.Cloud} contains the main configuration values for running
 * slaves on DigitalOcean, e.g. apiKey/clientId to connect to the API.
 *
 * The {@link com.dubture.jenkins.digitalocean.Cloud#provision(hudson.model.Label, int)} method will be called
 * by Jenkins as soon as a new slave needs to be provisioned.
 *
 * The number of
 *
 * @author robert.gruendler@dubture.com
 */
public class Cloud extends AbstractCloudImpl {

    public static final String SLAVE_NAME_REGEX = "^jenkins-\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}$";

    /**
     * The DigitalOcean API auth token
     * @see "https://developers.digitalocean.com/documentation/v2/#authentication"
     */
    private final String authToken;

    /**
     * The SSH key to be added to the new droplet.
     */
    private final Integer sshKeyId;


    /**
     * The SSH private key associated with the selected SSH key
     */
    private final String privateKey;

    /**
     * List of {@link com.dubture.jenkins.digitalocean.SlaveTemplate}
     */
    private final List<? extends SlaveTemplate> templates;

    /**
     * Currently provisioned droplets
     */
    private static final Map<String, Integer> provisioningDroplets = new HashMap<String, Integer>();

    private static final Logger LOGGER = Logger.getLogger(Cloud.class.getName());

    /**
     * Constructor parameters are injected via jelly in the jenkins global configuration
     * @param name A name associated with this cloud configuration
     * @param authToken A DigitalOcean V2 API authentication token, generated on their website.
     * @param privateKey An RSA private key in text format
     * @param sshKeyId An identifier (name) for an SSH key known to DigitalOcean
     * @param instanceCapStr the maximum number of instances that can be started
     * @param templates the templates for this cloud
     */
    @DataBoundConstructor
    public Cloud(String name, String authToken, String privateKey, String sshKeyId, String instanceCapStr, List<? extends SlaveTemplate> templates) {
        super(name, instanceCapStr);

        LOGGER.log(Level.INFO, "Constructing new Cloud(name = {0}, <token>, <privateKey>, <keyId>, instanceCap = {1}, ...)", new Object[] { name, instanceCapStr});

        this.authToken = authToken;
        this.privateKey = privateKey;
        this.sshKeyId = Integer.parseInt(sshKeyId);

        if(templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        LOGGER.info("Creating DigitalOcean cloud with " + this.templates.size() + " templates");

        readResolve();
    }

    protected Object readResolve() {
        for (SlaveTemplate template : templates) {
            template.parent = this;
        }
        return this;
    }

    /**
     * Count the number of droplets provisioned with the specified Image ID
     * @param imageId an image slug to identify the slaves to count
     * @return a count of active or new droplets
     * @throws RequestUnsuccessfulException
     * @throws DigitalOceanException
     */
    public int countCurrentDropletsSlaves(String imageId) throws RequestUnsuccessfulException, DigitalOceanException {
        LOGGER.log(Level.INFO, "countCurrentDropletsSlaves(" + imageId + ")");

        List<Droplet> availableDroplets = getDroplets();
        int count = 0;

        for (Droplet droplet : availableDroplets) {
            if (imageId == null || imageId.equals(droplet.getImage().getId().toString())) {
                if ((droplet.isActive() || droplet.isNew()) && droplet.getName().matches(SLAVE_NAME_REGEX)) {
                    count++;
                }
            }
        }

        return count;
    }

    /**
     * Fetches a list of all available droplets. The implementation will fetch all pages and return a single list
     * of droplets.
     * @return a list of all available droplets.
     * @throws DigitalOceanException
     * @throws RequestUnsuccessfulException
     */
    private List<Droplet> getDroplets() throws DigitalOceanException, RequestUnsuccessfulException {
        LOGGER.log(Level.INFO, "Listing all droplets");
        DigitalOceanClient apiClient = new DigitalOceanClient(this.authToken);
        List<Droplet> availableDroplets = newArrayList();
        Droplets droplets;
        int page = 1;

        do {
            droplets = apiClient.getAvailableDroplets(page);
            availableDroplets.addAll(droplets.getDroplets());
            page += 1;
        }
        while (droplets.getMeta().getTotal() > page);

        return availableDroplets;
    }

    /**
     * Adds an image to the list of currently provisioned droplets
     *
     * @param imageId the image slug of the provisioned slave
     * @return whether the slave can be / has been provisioned (not sure which TBH)
     */
    private boolean addProvisionedSlave(String imageId) {

        LOGGER.log(Level.INFO, "Adding provisioned slave " + imageId);

        int estimatedTotalSlaves;
        int estimatedDropletSlaves;
        try {
            estimatedTotalSlaves = countCurrentDropletsSlaves(null);
            estimatedDropletSlaves = countCurrentDropletsSlaves(imageId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
            return false;
        }

        synchronized (provisioningDroplets) {
            int currentProvisioning;

            for (int dropletCount : provisioningDroplets.values()) {
                estimatedTotalSlaves += dropletCount;
            }
            try {
                currentProvisioning = provisioningDroplets.get(imageId);
            }
            catch (NullPointerException npe) {
                currentProvisioning = 0;
            }

            estimatedDropletSlaves += currentProvisioning;

            if(estimatedTotalSlaves >= getInstanceCap()) {
                LOGGER.log(Level.INFO, "Total instance cap of " + getInstanceCap() + " reached, not provisioning.");
                return false; // maxed out
            }

            if (estimatedDropletSlaves >= getInstanceCap()) {
                LOGGER.log(Level.INFO, "Droplet Instance cap of " + getInstanceCap() + " reached for imageId " + imageId + ", not provisioning.");
                return false; // maxed out
            }

            LOGGER.log(Level.INFO,
                    "Provisioning for " + imageId + "; " +
                            "Estimated number of total slaves: "
                            + estimatedTotalSlaves + "; " +
                            "Estimated number of slaves for imageId "
                            + imageId + ": " + estimatedDropletSlaves
            );

            provisioningDroplets.put(imageId, currentProvisioning + 1);
            return true;
        }
    }


    /**
     * The actual logic for provisioning a new droplet when it's needed by Jenkins.
     *
     * @param label
     * @param excessWorkload
     * @return
     */
    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {

        if (label != null) {
            LOGGER.info("Provisioning digitalocean droplet for label " + label.getName() + ", excessWorkload " + excessWorkload);
        } else {
            LOGGER.info("Provisioning digitalocean droplet without label, excessWorkload " + excessWorkload);
        }

        List<NodeProvisioner.PlannedNode> nodes = new ArrayList<NodeProvisioner.PlannedNode>();
        final DigitalOceanClient apiClient = new DigitalOceanClient(authToken);
        final SlaveTemplate template = getTemplate(label);

        try {
            while (excessWorkload > 0) {
                if (!addProvisionedSlave(template.getImageId())) {
                    break;
                }

                final String dropletName = template.createDropletName();
                nodes.add(new NodeProvisioner.PlannedNode(dropletName, Computer.threadPoolForRemoting.submit(new Callable<Node>() {

                    public Node call() throws Exception {
                        // TODO: record the output somewhere
                        try {
                            Slave slave = template.provision(apiClient, dropletName, privateKey, sshKeyId, new StreamTaskListener(System.out, Charset.defaultCharset()));
                            Jenkins.getInstance().addNode(slave);
                            slave.toComputer().connect(false).get();
                            return slave;
                        } finally {
                            decrementDropletSlaveProvision(template.getImageId());
                        }
                    }
                }), template.getNumExecutors()));

                excessWorkload -= template.getNumExecutors();
            }

            LOGGER.info("Provisioning " + nodes.size() + " DigitalOcean nodes");
            return nodes;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Decrement the count for the currently provisioned droplets with the specified Image ID.
     *
     * @param imageId the image slug of the de-provisioned slave
     */
    private void decrementDropletSlaveProvision(String imageId) {
        LOGGER.log(Level.INFO, "Decrementing provision slave " + imageId);
        synchronized (provisioningDroplets) {
            int currentProvisioning;
            try {
                currentProvisioning = provisioningDroplets.get(imageId);
            } catch(NullPointerException npe) {
                return;
            }
            provisioningDroplets.put(imageId, Math.max(currentProvisioning - 1, 0));
        }
    }

    @Override
    public boolean canProvision(Label label) {
        return Optional.fromNullable(getTemplate(label)).isPresent();
    }

    public List<SlaveTemplate> getTemplates() {
        LOGGER.log(Level.INFO, "getTemplates");
        return Collections.unmodifiableList(templates);
    }

    public SlaveTemplate getTemplate(Label label) {
        for (SlaveTemplate t : templates) {
            if(label == null && t.getLabelSet().size() != 0) {
                continue;
            }
            if((label == null && t.getLabelSet().size() == 0) || label.matches(t.getLabelSet())) {
                return t;
            }
        }
        return null;
    }

    public String getName() {
        return name;
    }

    public String getAuthToken() {
        return authToken;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public int getSshKeyId() {
        return sshKeyId;
    }

    public DigitalOceanClient getApiClient() {
        return new DigitalOceanClient(authToken);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<hudson.slaves.Cloud> {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Digital Ocean";
        }

        public FormValidation doTestConnection(@QueryParameter String authToken) throws IOException, ServletException {
            try {
                DigitalOceanClient client = new DigitalOceanClient(authToken);
                client.getAvailableDroplets(1);
                return FormValidation.ok("Digitalocean API request succeeded.");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to connect to DigitalOcean API", e);
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (Strings.isNullOrEmpty(name)) {
                return FormValidation.error("Name must be set");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckAuthToken(@QueryParameter String authToken) {
            if (Strings.isNullOrEmpty(authToken)) {
                return FormValidation.error("Auth token must be set");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckPrivateKey(@QueryParameter String value) throws IOException, ServletException {
            boolean hasStart=false,hasEnd=false;
            BufferedReader br = new BufferedReader(new StringReader(value));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                    hasStart=true;
                if (line.equals("-----END RSA PRIVATE KEY-----"))
                    hasEnd=true;
            }
            if(!hasStart)
                return FormValidation.error("This doesn't look like a private key at all");
            if(!hasEnd)
                return FormValidation.error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
            return FormValidation.ok();
        }

        public ListBoxModel doFillSshKeyIdItems(@QueryParameter String authToken) throws Exception {

            List<Key> availableSizes = getAvailableKeys(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Key image : availableSizes) {
                model.add(image.getName(), image.getId().toString());
            }

            return model;
        }

        private List<Key> getAvailableKeys(String authToken) throws RequestUnsuccessfulException, DigitalOceanException {

            DigitalOceanClient client = new DigitalOceanClient(authToken);
            List<Key> availableKeys = new ArrayList<Key>();

            Keys keys;
            int page = 1;

            do {
                keys = client.getAvailableKeys(page);
                availableKeys.addAll(keys.getKeys());
                page += 1;
            }
            while (keys.getMeta().getTotal() > page);

            return availableKeys;
        }
    }

}
