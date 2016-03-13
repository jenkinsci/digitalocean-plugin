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

import com.google.common.base.Strings;
import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.Key;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
public class Cloud extends hudson.slaves.Cloud {
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

    private final Integer instanceCap;

    /**
     * List of {@link com.dubture.jenkins.digitalocean.SlaveTemplate}
     */
    private final List<? extends SlaveTemplate> templates;

    private static final Logger LOGGER = Logger.getLogger(Cloud.class.getName());

    /**
     * Sometimes nodes can be provisioned very fast (or in parallel), leading to more nodes being
     * provisioned than the instance cap allows, as they all check DigitalOcean at about the same time
     * right before provisioning and see that instance cap was not reached yet. So, for example, there
     * might be a situation where 2 nodes see that 1 more node can be provisioned before the instance cap
     * is reached, and they both happily provision, making one more node being provisioned than the instance
     * cap allows. Thus we need a synchronization, so that only one node at a time could be provisioned, to
     * remove the race condition.
     */
    private static final Object provisionSynchronizor = new Object();

    /**
     * Constructor parameters are injected via jelly in the jenkins global configuration
     * @param name A name associated with this cloud configuration
     * @param authToken A DigitalOcean V2 API authentication token, generated on their website.
     * @param privateKey An RSA private key in text format
     * @param sshKeyId An identifier (name) for an SSH key known to DigitalOcean
     * @param instanceCap the maximum number of instances that can be started
     * @param templates the templates for this cloud
     */
    @DataBoundConstructor
    public Cloud(String name, String authToken, String privateKey, String sshKeyId, String instanceCap, List<? extends SlaveTemplate> templates) {
        super(name);

        LOGGER.log(Level.INFO, "Constructing new Cloud(name = {0}, <token>, <privateKey>, <keyId>, instanceCap = {1}, ...)", new Object[]{name, instanceCap});

        this.authToken = authToken;
        this.privateKey = privateKey;
        this.sshKeyId = Integer.parseInt(sshKeyId);
        this.instanceCap = Integer.parseInt(instanceCap);

        if (templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        LOGGER.info("Creating DigitalOcean cloud with " + this.templates.size() + " templates");
    }

    public boolean isInstanceCapReached() throws RequestUnsuccessfulException, DigitalOceanException {
        if (instanceCap == 0) {
            return false;
        }

        int slaveTotalInstanceCap = 0;
        for (SlaveTemplate t : templates) {
            int slaveInstanceCap = t.getInstanceCap();
            if (slaveInstanceCap == 0) {
                slaveTotalInstanceCap = Integer.MAX_VALUE;
                break;
            } else {
                slaveTotalInstanceCap += t.getInstanceCap();
            }
        }

        int count = 0;

        LOGGER.log(Level.INFO, "cloud limit check");

        List<Node> nodes = Jenkins.getInstance().getNodes();
        for (Node n : nodes) {
            if (DropletName.isDropletInstanceOfCloud(n.getDisplayName(), name)) {
                count ++;
            }
        }

        if (count >= Math.min(instanceCap, slaveTotalInstanceCap)) {
            return true;
        }

        List<Droplet> availableDroplets = DigitalOcean.getDroplets(authToken);

        count = 0;
        for (Droplet droplet : availableDroplets) {
            if (droplet.isActive() || droplet.isNew()) {
                if (DropletName.isDropletInstanceOfCloud(droplet.getName(), name)) {
                    count ++;
                }
            }
        }

        return count >= Math.min(instanceCap, slaveTotalInstanceCap);
    }

    /**
     * The actual logic for provisioning a new droplet when it's needed by Jenkins.
     *
     * @param label
     * @param excessWorkload
     * @return
     */
    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(final Label label, int excessWorkload) {
        synchronized (provisionSynchronizor) {
            List<NodeProvisioner.PlannedNode> provisioningNodes = new ArrayList<NodeProvisioner.PlannedNode>();
            try {
                while (excessWorkload > 0) {

                    if (isInstanceCapReached()) {
                        LOGGER.log(Level.INFO, "Instance cap of " + getInstanceCap() + " reached, not provisioning.");
                        break;
                    }

                    final SlaveTemplate template = getTemplateBelowInstanceCap(label);
                    if (template == null) {
                        break;
                    }

                    final String dropletName = DropletName.generateDropletName(name, template.getName());

                    provisioningNodes.add(new NodeProvisioner.PlannedNode(dropletName, Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                        public Node call() throws Exception {
                            Slave slave;
                            synchronized (provisionSynchronizor) {
                                if (isInstanceCapReached()) {
                                    LOGGER.log(Level.INFO, "Instance cap of " + getInstanceCap() + " reached, not provisioning.");
                                    return null;
                                }
                                slave = template.provision(dropletName, name, authToken, privateKey, sshKeyId);
                            }
                            Jenkins.getInstance().addNode(slave);
                            slave.toComputer().connect(false).get();
                            return slave;
                        }
                    }), template.getNumExecutors()));

                    excessWorkload -= template.getNumExecutors();
                }

                LOGGER.info("Provisioning " + provisioningNodes.size() + " DigitalOcean nodes");

                return provisioningNodes;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e.getMessage(), e);
                return Collections.emptyList();
            }
        }
    }

    @Override
    public boolean canProvision(Label label) {
        synchronized (provisionSynchronizor) {
            try {
                SlaveTemplate template = getTemplateBelowInstanceCap(label);
                if (template == null) {
                    LOGGER.log(Level.INFO, "No slaves could provision for label " + label.getDisplayName() + " because they either dodn't support such a label or have reached the instance cap.");
                    return false;
                }

                if (isInstanceCapReached()) {
                    LOGGER.log(Level.INFO, "Instance cap of " + getInstanceCap() + " reached, not provisioning.");
                    return false;
                }


            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e.getMessage(), e);
            }

            return true;
        }
    }

    public List<SlaveTemplate> getTemplates(Label label) {
        List<SlaveTemplate> matchingTemplates = new ArrayList<SlaveTemplate>();

        for (SlaveTemplate t : templates) {
            if (label == null && t.getLabelSet().size() != 0) {
                continue;
            }
            if ((label == null && t.getLabelSet().size() == 0) || label.matches(t.getLabelSet())) {
                matchingTemplates.add(t);
            }
        }

        return matchingTemplates;
    }

    public SlaveTemplate getTemplateBelowInstanceCap(Label label) {
        List<SlaveTemplate> matchingTempaltes = getTemplates(label);

        try {
            for (SlaveTemplate t : matchingTempaltes) {
                if (!t.isInstanceCapReached(authToken, name)) {
                    return t;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
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

    public int getInstanceCap() {
        return instanceCap;
    }

    public DigitalOceanClient getApiClient() {
        return new DigitalOceanClient(authToken);
    }

    public List<SlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<hudson.slaves.Cloud> {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Digital Ocean";
        }

        public FormValidation doTestConnection(@QueryParameter String authToken) {
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
                return FormValidation.error("Must be set");
            } else if (!DropletName.isValidCloudName(name)) {
                return FormValidation.error("Must consist of A-Z, a-z, 0-9 and . symbols");
            } else {
                return FormValidation.ok();
            }
        }

        public static FormValidation doCheckAuthToken(@QueryParameter String authToken) {
            if (Strings.isNullOrEmpty(authToken)) {
                return FormValidation.error("Auth token must be set");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckPrivateKey(@QueryParameter String value) throws IOException {
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

        public FormValidation doCheckSshKeyId(@QueryParameter String authToken) {
            return doCheckAuthToken(authToken);
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String instanceCap) {
            if (Strings.isNullOrEmpty(instanceCap)) {
                return FormValidation.error("Instance cap must be set");
            } else {
                int instanceCapNumber;

                try {
                    instanceCapNumber = Integer.parseInt(instanceCap);
                } catch (Exception e) {
                    return FormValidation.error("Instance cap must be a number");
                }

                if (instanceCapNumber < 0) {
                    return FormValidation.error("Instance cap must be a positive number");
                }

                return FormValidation.ok();
            }
        }

        public ListBoxModel doFillSshKeyIdItems(@QueryParameter String authToken) throws RequestUnsuccessfulException, DigitalOceanException {
            List<Key> availableSizes = DigitalOcean.getAvailableKeys(authToken);
            ListBoxModel model = new ListBoxModel();

            for (Key image : availableSizes) {
                model.add(image.getName(), image.getId().toString());
            }

            return model;
        }
    }
}
