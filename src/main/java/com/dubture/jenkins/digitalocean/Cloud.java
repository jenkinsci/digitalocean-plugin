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
import com.myjeeva.digitalocean.exception.AccessDeniedException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.exception.ResourceNotFoundException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.SshKey;
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
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    /**
     * The DigitalOcean API key
     * @see "https://cloud.digitalocean.com/api_access"
     */
    private final String apiKey;

    /**
     * The DigitalOcean Client ID
     * @see "https://cloud.digitalocean.com/api_access"
     */
    private final String clientId;

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
    private static HashMap<Integer, Integer> provisioningDroplets = new HashMap<Integer, Integer>();

    private static final Logger LOGGER = Logger.getLogger(Cloud.class.getName());

    /**
     * Constructor parameters are injected via jelly in the jenkins global configuration
     * @param name
     * @param apiKey
     * @param clientId
     * @param privateKey
     * @param sshKeyId
     * @param instanceCapStr
     * @param templates
     */
    @DataBoundConstructor
    public Cloud(String name, String apiKey, String clientId, String privateKey, String sshKeyId, String instanceCapStr, List<? extends SlaveTemplate> templates) {
        super(name, instanceCapStr);

        this.apiKey = apiKey;
        this.clientId = clientId;
        this.privateKey = privateKey;
        this.sshKeyId = Integer.parseInt(sshKeyId);

        if(templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        LOGGER.info("Creating DigitalOcean cloud with " + templates.size() + " templates");

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
     * @param imageId
     * @return
     * @throws RequestUnsuccessfulException
     * @throws AccessDeniedException
     * @throws ResourceNotFoundException
     */
    public int countCurrentDropletsSlaves(Integer imageId) throws RequestUnsuccessfulException, AccessDeniedException, ResourceNotFoundException {

        int count = 0;
        DigitalOceanClient apiClient = new DigitalOceanClient(clientId, apiKey);
        List<Droplet> availableDroplets = apiClient.getAvailableDroplets();

        for (Droplet droplet : availableDroplets) {
            if (imageId == null || imageId.equals(droplet.getImageId())) {
                if ("active".equals(droplet.getStatus()) || "new".equals(droplet.getStatus()) /*droplet.isActive() || droplet.isNew()*/) {
                    count++;
                }
            }
        }

        return count;
    }

    /**
     * Adds an imageId to the list of currently provisioned droplets
     *
     * @param imageId
     * @return
     */
    private boolean addProvisionedSlave(Integer imageId) {

        int estimatedTotalSlaves;
        int estimatedAmiSlaves;
        try {
            estimatedTotalSlaves = countCurrentDropletsSlaves(null);
            estimatedAmiSlaves = countCurrentDropletsSlaves(imageId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
            return false;
        }

        synchronized (provisioningDroplets) {
            int currentProvisioning;

            for (int amiCount : provisioningDroplets.values()) {
                estimatedTotalSlaves += amiCount;
            }
            try {
                currentProvisioning = provisioningDroplets.get(imageId);
            }
            catch (NullPointerException npe) {
                currentProvisioning = 0;
            }

            estimatedAmiSlaves += currentProvisioning;

            if(estimatedTotalSlaves >= getInstanceCap()) {
                LOGGER.log(Level.INFO, "Total instance cap of " + getInstanceCap() + " reached, not provisioning.");
                return false; // maxed out
            }

            if (estimatedAmiSlaves >= getInstanceCap()) {
                LOGGER.log(Level.INFO, "AMI Instance cap of " + getInstanceCap() + " reached for imageId " + imageId + ", not provisioning.");
                return false; // maxed out
            }

            LOGGER.log(Level.INFO,
                    "Provisioning for AMI " + imageId + "; " +
                            "Estimated number of total slaves: "
                            + String.valueOf(estimatedTotalSlaves) + "; " +
                            "Estimated number of slaves for imageId "
                            + imageId + ": "
                            + String.valueOf(estimatedAmiSlaves)
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
        final DigitalOceanClient apiClient = new DigitalOceanClient(clientId, apiKey);
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
                            decrementDropletSlaveProvision(template.imageId);
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
     * @param imageId
     */
    private void decrementDropletSlaveProvision(int imageId) {
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

    public String getApiKey() {
        return apiKey;
    }

    public String getClientId() {
        return clientId;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public int getSshKeyId() {
        return sshKeyId;
    }

    public DigitalOceanClient getApiClient() {
        return new DigitalOceanClient(clientId, apiKey);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<hudson.slaves.Cloud> {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Digital Ocean";
        }

        public FormValidation doTestConnection(@QueryParameter String apiKey, @QueryParameter String clientId) throws IOException, ServletException {
            try {
                DigitalOceanClient client = new DigitalOceanClient(clientId, apiKey);
                client.getAvailableDroplets();
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

        public FormValidation doCheckApiKey(@QueryParameter String apiKey) {
            if (Strings.isNullOrEmpty(apiKey)) {
                return FormValidation.error("API key must be set");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckClientId(@QueryParameter String clientId) {
            if (Strings.isNullOrEmpty(clientId)) {
                return FormValidation.error("Client ID must be set");
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


        public ListBoxModel doFillSshKeyIdItems(@QueryParameter String apiKey, @QueryParameter String clientId) throws Exception {

            DigitalOceanClient client = new DigitalOceanClient(clientId, apiKey);

            List<SshKey> availableSizes = client.getAvailableSshKeys();
            ListBoxModel model = new ListBoxModel();

            for (SshKey image : availableSizes) {
                model.add(image.getName(), image.getId().toString());
            }

            return model;
        }
    }
}
