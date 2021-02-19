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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.common.base.Strings;
import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.Key;
import com.thoughtworks.xstream.converters.UnmarshallingContext;

import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import hudson.util.XStream2;
import jenkins.model.Jenkins;

/**
 * The {@link DigitalOceanCloud} contains the main configuration values for running
 * slaves on DigitalOcean, e.g. apiKey/clientId to connect to the API.
 * <p>
 * The {@link DigitalOceanCloud#provision(hudson.model.Label, int)} method will be called
 * by Jenkins as soon as a new slave needs to be provisioned.
 * <p>
 * The number of
 *
 * @author robert.gruendler@dubture.com
 */
public class DigitalOceanCloud extends Cloud {
    /**
     * @See authTokenCredentialId
     */
    @Deprecated
    private transient String authToken;
    /**
     * The DigitalOcean API auth token
     *
     * @see "https://developers.digitalocean.com/documentation/v2/#authentication"
     */
    private String authTokenCredentialId;

    /**
     * The SSH key to be added to the new droplet.
     */
    private final Integer sshKeyId;

    /**
     * @See privateKeyCredentialId
     */
    @Deprecated
    private transient String privateKey;
    /**
     * The SSH private key associated with the selected SSH key
     */
    private String privateKeyCredentialId;

    private final Integer instanceCap;

    private Boolean usePrivateNetworking;

    private final Integer timeoutMinutes;

    private Integer connectionRetryWait;

    /**
     * List of {@link com.dubture.jenkins.digitalocean.SlaveTemplate}
     */
    private final List<? extends SlaveTemplate> templates;

    private static final Logger LOGGER = Logger.getLogger(DigitalOceanCloud.class.getName());

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
     *
     * @param name                 A name associated with this cloud configuration
     * @param authToken            A DigitalOcean V2 API authentication token, generated on their website.
     * @param privateKey           An RSA private key in text format
     * @param sshKeyId             An identifier (name) for an SSH key known to DigitalOcean
     * @param instanceCap          the maximum number of instances that can be started
     * @param usePrivateNetworking Whether to use private networking to connect to the cloud.
     * @param timeoutMinutes       the timeout in minutes.
     * @param connectionRetryWait  the time to wait for SSH connections to work
     * @param templates            the templates for this cloud
     */
    @Deprecated
    public DigitalOceanCloud(String name,
                             String authToken,
                             String privateKey,
                             String sshKeyId,
                             String instanceCap,
                             Boolean usePrivateNetworking,
                             String timeoutMinutes,
                             String connectionRetryWait,
                             List<? extends SlaveTemplate> templates) {
        this(name, sshKeyId, instanceCap, usePrivateNetworking, timeoutMinutes, connectionRetryWait, templates);
    }

    /**
     * Constructor parameters are injected via jelly in the jenkins global configuration
     *
     * @param name                 A name associated with this cloud configuration
     * @param authToken            A DigitalOcean V2 API authentication token, generated on their website.
     * @param privateKey           An RSA private key in text format
     * @param sshKeyId             An identifier (name) for an SSH key known to DigitalOcean
     * @param instanceCap          the maximum number of instances that can be started
     * @param usePrivateNetworking Whether to use private networking to connect to the cloud.
     * @param timeoutMinutes       the timeout in minutes.
     * @param connectionRetryWait  the time to wait for SSH connections to work
     * @param templates            the templates for this cloud
     */
    @DataBoundConstructor
    public DigitalOceanCloud(String name,
                             String sshKeyId,
                             String instanceCap,
                             Boolean usePrivateNetworking,
                             String timeoutMinutes,
                             String connectionRetryWait,
                             List<? extends SlaveTemplate> templates) {
        super(name);

        LOGGER.log(Level.INFO, "Constructing new DigitalOceanCloud(name = {0}, <token>, <privateKey>, <keyId>, instanceCap = {1}, ...)", new Object[]{name, instanceCap});

        this.authToken = authToken;
        this.privateKey = privateKey;
        this.sshKeyId = sshKeyId == null ? 0 : Integer.parseInt(sshKeyId);
        this.instanceCap = instanceCap == null ? 0 : Integer.parseInt(instanceCap);
        this.usePrivateNetworking = usePrivateNetworking;
        this.timeoutMinutes = timeoutMinutes == null || timeoutMinutes.isEmpty() ? 5 : Integer.parseInt(timeoutMinutes);
        this.connectionRetryWait = connectionRetryWait == null || connectionRetryWait.isEmpty() ? 10 : Integer.parseInt(connectionRetryWait);

        if (templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        LOGGER.info("Creating DigitalOcean cloud with " + this.templates.size() + " templates");
    }

    @DataBoundSetter
    public void setPrivateKeyCredentialId(String credentialId) {
      this.privateKeyCredentialId = credentialId;
    }

    public String getPrivateKeyCredentialId() {
      return this.privateKeyCredentialId;
    }

    @DataBoundSetter
    public void setAuthTokenCredentialId(String credentialId) {
      this.authTokenCredentialId = credentialId;
    }

    public String getAuthTokenCredentialId() {
      return this.authTokenCredentialId;
    }

    @Override
    public String getDisplayName() {
        return this.name;
    }

    private boolean isInstanceCapReachedLocal() {
        if (instanceCap == 0) {
            return false;
        }

        int count = 0;

        LOGGER.log(Level.INFO, "cloud limit check");

        List<Node> nodes = Jenkins.getInstance().getNodes();
        for (Node n : nodes) {
            if (DropletName.isDropletInstanceOfCloud(n.getDisplayName(), name)) {
                count++;
            }
        }

        return count >= Math.min(instanceCap, getSlaveInstanceCap());
    }

    private boolean isInstanceCapReachedRemote(List<Droplet> droplets) {

        int count = 0;

        LOGGER.log(Level.INFO, "cloud limit check");

        for (Droplet droplet : droplets) {
            if (droplet.isActive() || droplet.isNew()) {
                if (DropletName.isDropletInstanceOfCloud(droplet.getName(), name)) {
                    count++;
                }
            }
        }

        return count >= Math.min(instanceCap, getSlaveInstanceCap());
    }

    private int getSlaveInstanceCap() {
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

        return slaveTotalInstanceCap;
    }

    /**
     * The actual logic for provisioning a new droplet when it's needed by Jenkins.
     *
     * @param label          name of the field
     * @param excessWorkload if the workload can be overloaded
     * @return the number of planned nodes
     */
    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(final Label label, int excessWorkload) {
        synchronized (provisionSynchronizor) {
            final String authToken = DigitalOceanCloud.getAuthTokenFromCredentialId(authTokenCredentialId);
            final String privateKey = DigitalOceanCloud.getPrivateKeyFromCredentialId(privateKeyCredentialId);
            List<NodeProvisioner.PlannedNode> provisioningNodes = new ArrayList<>();
            try {
                while (excessWorkload > 0) {
                    List<Droplet> droplets = DigitalOcean.getDroplets(authToken);

                    if (isInstanceCapReachedLocal() || isInstanceCapReachedRemote(droplets)) {
                        LOGGER.log(Level.INFO, "Instance cap reached, not provisioning.");
                        break;
                    }

                    final SlaveTemplate template = getTemplateBelowInstanceCap(droplets, label);
                    if (template == null) {
                        break;
                    }

                    final String dropletName = DropletName.generateDropletName(name, template.getName());

                    final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(this.name, template.getName(), dropletName);
                    provisioningNodes.add(new TrackedPlannedNode(provisioningId, template.getNumExecutors(), Computer.threadPoolForRemoting.submit(() -> {
                        Slave slave;
                        synchronized (provisionSynchronizor) {
                            List<Droplet> droplets1 = DigitalOcean.getDroplets(authToken);

                            if (isInstanceCapReachedLocal() || isInstanceCapReachedRemote(droplets1)) {
                                LOGGER.log(Level.INFO, "Instance cap reached, not provisioning.");
                                return null;
                            }

                            slave = template.provision(provisioningId, dropletName, name, authToken, privateKey,
                                    sshKeyId, droplets1, usePrivateNetworking);
                            Jenkins.getInstance().addNode(slave);
                        }
                        slave.toComputer().connect(false).get();
                        return slave;
                    })));

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
        // NB: this must be lock-less, lest it causes deadlocks. This method may get called from Queue locked
        // call-chains (e.g. Node adding/removing) and since they may not be lock protected they have the potential
        // of deadlocking on our provisionLock.
        // Also we'll treat this like a hint. We *may* be able to provision a node, but we *can* provision that type
        // of label in general. It may later turn out that we can not currently provision though, provision() would
        // then return an empty list of planned nodes, and from what I can tell jenkins core will eventually retry
        // provisioning. It's very much unclear if this is indeed better, a lock timeout may proof more effectively.
        // This also doesn't take into account the actual capacity, as that too gets checked during provision.
        boolean can = !getTemplates(label).isEmpty();
        LOGGER.log(Level.INFO, "canProvision " + label + " :: " + can);
        return can;
    }

    private List<SlaveTemplate> getTemplates(Label label) {
        List<SlaveTemplate> matchingTemplates = new ArrayList<>();

        for (SlaveTemplate t : templates) {
            if ((label == null && t.getLabelSet().size() == 0) ||
                    (label == null && t.isLabellessJobsAllowed()) ||
                    (label != null && label.matches(t.getLabelSet()))) {
                matchingTemplates.add(t);
            }
        }

        return matchingTemplates;
    }

    private SlaveTemplate getTemplateBelowInstanceCap(List<Droplet> droplets, Label label) {
        List<SlaveTemplate> matchingTemplates = getTemplates(label);

        try {
            for (SlaveTemplate t : matchingTemplates) {
                if (!t.isInstanceCapReachedLocal(name) && !t.isInstanceCapReachedRemote(droplets, name)) {
                    return t;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
        }

        return null;
    }

    private SlaveTemplate getTemplateBelowInstanceCapLocal(Label label) {
        List<SlaveTemplate> matchingTemplates = getTemplates(label);

        try {
            for (SlaveTemplate t : matchingTemplates) {
                if (!t.isInstanceCapReachedLocal(name)) {
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

    public static String getAuthTokenFromCredentialId(String credentialId) {
        if (StringUtils.isBlank(credentialId)) {
            return null;
        }
        StringCredentials cred = (StringCredentials) CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(StringCredentials.class, Jenkins.get(), ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialId));
        if (cred == null) {
          return null;
        }
        return cred.getSecret().getPlainText();
    }

    public static String getPrivateKeyFromCredentialId(String credentialId) {
        if (StringUtils.isBlank(credentialId)) {
            return null;
        }
        SSHUserPrivateKey cred = (SSHUserPrivateKey) CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(SSHUserPrivateKey.class, Jenkins.get(), ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialId));
        if (cred == null) {
          return null;
        }
        return cred.getPrivateKeys().stream().findFirst().get();
    }


    public int getSshKeyId() {
        return sshKeyId;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public DigitalOceanClient getApiClient() {
        final String authToken = DigitalOceanCloud.getAuthTokenFromCredentialId(authTokenCredentialId);
        return new DigitalOceanClient(authToken);
    }

    public List<SlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    public Integer getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public Integer getConnectionRetryWait() {
        return connectionRetryWait;
    }

    public Boolean getUsePrivateNetworking() {
        return usePrivateNetworking;
    }

    public static final class ConverterImpl extends XStream2.PassthruConverter<DigitalOceanCloud> {
        public ConverterImpl(XStream2 xstream) {
            super(xstream);
        }

        @Override
        public boolean canConvert(Class type) {
            return type == DigitalOceanCloud.class;
        }

        @Override
        protected void callback(DigitalOceanCloud obj, UnmarshallingContext context) {
            if (null == obj.connectionRetryWait) {
                // Introduced in 0.14.
                obj.connectionRetryWait = 10;
            }
            if (null == obj.usePrivateNetworking) {
                // Introduced in 0.14.
                obj.usePrivateNetworking = false;
            }
        }
    }

    // borrowed from ec2-plugin
    private void migratePrivateSshKeyToCredential(String privateKey){
        // GET matching private key credential from Credential API if exists
        Optional<SSHUserPrivateKey> keyCredential = SystemCredentialsProvider.getInstance().getCredentials()
                .stream()
                .filter((cred) -> cred instanceof SSHUserPrivateKey)
                .filter((cred) -> ((SSHUserPrivateKey)cred).getPrivateKey().trim().equals(privateKey.trim()))
                .map(cred -> (SSHUserPrivateKey)cred)
                .findFirst();

        if (keyCredential.isPresent()){
            // SET this.sshKeysCredentialsId with the found credential
            privateKeyCredentialId = keyCredential.get().getId();
            return;
        }

        // CREATE new credential
        String credsId = UUID.randomUUID().toString();

        SSHUserPrivateKey sshKeyCredentials = new BasicSSHUserPrivateKey(CredentialsScope.SYSTEM, credsId, "key",
                new BasicSSHUserPrivateKey.PrivateKeySource() {
                    @NonNull
                    @Override
                    public List<String> getPrivateKeys() {
                        return Collections.singletonList(privateKey.trim());
                    }
                }, "", "DigitalOcean Cloud Private Key - " + getDisplayName());

        addNewGlobalCredential(sshKeyCredentials);

        privateKeyCredentialId = credsId;
    }

    protected Object readResolve() {
        if (this.privateKey != null ){
            migratePrivateSshKeyToCredential(this.privateKey);
        }
        this.privateKey = null; // This enforces it not to be persisted and that CasC will never output privateKey on export

        if (this.authToken != null) {
            SystemCredentialsProvider systemCredentialsProvider = SystemCredentialsProvider.getInstance();

            // ITERATE ON EXISTING CREDS AND DON'T CREATE IF EXIST
            for (Credentials credentials: systemCredentialsProvider.getCredentials()) {
                if (credentials instanceof StringCredentials) {
                    StringCredentials doCreds = (StringCredentials) credentials;
                    if (this.authToken.equals(doCreds.getSecret().toString())) {
                        this.authTokenCredentialId = doCreds.getId();
                        this.authToken = null;
                        return this;
                    }
                }
            }

            // CREATE
            String credsId = UUID.randomUUID().toString();
            addNewGlobalCredential(new StringCredentialsImpl(CredentialsScope.SYSTEM, credsId, "EC2 Cloud - " + getDisplayName(), Secret.fromString(this.authToken)));
            this.authTokenCredentialId = credsId;
            this.authToken = null;


            // PROBLEM, GLOBAL STORE NOT FOUND
            LOGGER.log(Level.WARNING, "DigitalOcean Plugin could not migrate credentials to the Jenkins Global Credentials Store, DigitalOcean Plugin for cloud {0} must be manually reconfigured", getDisplayName());
        }

        return this;
    }

    private void addNewGlobalCredential(Credentials credentials){
        for (CredentialsStore credentialsStore: CredentialsProvider.lookupStores(Jenkins.get())) {
            if (credentialsStore instanceof  SystemCredentialsProvider.StoreImpl) {
                try {
                    credentialsStore.addCredentials(Domain.global(), credentials);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Exception converting legacy configuration to the new credentials API", e);
                }
            }

        }
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<hudson.slaves.Cloud> {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Digital Ocean";
        }

        public FormValidation doTestConnection(@QueryParameter String authTokenCredentialId) {
            try {
                DigitalOceanClient client = new DigitalOceanClient(getAuthTokenFromCredentialId(authTokenCredentialId));
                client.getAvailableDroplets(1, 10);
                return FormValidation.ok("Digital Ocean API request succeeded.");
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
            boolean hasStart = false, hasEnd = false;
            BufferedReader br = new BufferedReader(new StringReader(value));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                    hasStart = true;
                if (line.equals("-----END RSA PRIVATE KEY-----"))
                    hasEnd = true;
            }
            if (!hasStart)
                return FormValidation.error("This doesn't look like a private key at all");
            if (!hasEnd)
                return FormValidation.error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
            return FormValidation.ok();
        }

        public FormValidation doCheckSshKeyId(@QueryParameter String authTokenCredentialId) {
            return doCheckAuthToken(getAuthTokenFromCredentialId(authTokenCredentialId));
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

        public ListBoxModel doFillSshKeyIdItems(@QueryParameter String authTokenCredentialId) throws RequestUnsuccessfulException, DigitalOceanException {
            ListBoxModel model = new ListBoxModel();
            if (Strings.isNullOrEmpty(authTokenCredentialId)) {
                // Do not even attempt to list the keys if we know the authToken isn't going to work.
                // It only produces useless errors.
                return model;
            }

            List<Key> availableSizes = DigitalOcean.getAvailableKeys(getAuthTokenFromCredentialId(authTokenCredentialId));
            for (Key key : availableSizes) {
                model.add(key.getName() + " (" + key.getFingerprint() + ")", key.getId().toString());
            }

            return model;
        }

        public ListBoxModel doFillPrivateKeyCredentialIdItems(@QueryParameter String credentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return result.includeCurrentValue(credentialsId);
            }
            return result
                .includeEmptyValue()
                .includeMatchingAs(Jenkins.getAuthentication(), Jenkins.get(), SSHUserPrivateKey.class, Collections.<DomainRequirement>emptyList(), CredentialsMatchers.always())
                .includeMatchingAs(ACL.SYSTEM, Jenkins.get(), SSHUserPrivateKey.class, Collections.<DomainRequirement>emptyList(), CredentialsMatchers.always())
                .includeCurrentValue(credentialsId);
        }

        public ListBoxModel doFillAuthTokenCredentialIdItems(@QueryParameter String credentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return result.includeCurrentValue(credentialsId);
            }
            return result
                .includeEmptyValue()
                .includeMatchingAs(Jenkins.getAuthentication(), Jenkins.get(), StringCredentials.class, Collections.<DomainRequirement>emptyList(), CredentialsMatchers.always())
                .includeMatchingAs(ACL.SYSTEM, Jenkins.get(), StringCredentials.class, Collections.<DomainRequirement>emptyList(), CredentialsMatchers.always())
                .includeCurrentValue(credentialsId);
        }
    }
}
