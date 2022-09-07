package com.dubture.jenkins.digitalocean;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.Collections;
import java.util.List;

import org.hamcrest.CoreMatchers;
import org.junit.Rule;
import org.junit.Test;

import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import jenkins.model.Jenkins;

public class ConfigurationAsCodeTest {

    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Test
    @ConfiguredWithCode("CloudEmpty.yml")
    public void testEmptyConfig() throws Exception {
        final DigitalOceanCloud doCloud = (DigitalOceanCloud) Jenkins.get().getCloud("dojenkins-");
        assertNotNull(doCloud);
        assertEquals(0, doCloud.getTemplates().size());
    }

    @Test
    @ConfiguredWithCode("happy.yml")
    public void testHappy() throws Exception {
        final DigitalOceanCloud doCloud = (DigitalOceanCloud) Jenkins.get().getCloud("dojenkins-happy");
        assertNotNull(doCloud);

        final List<SlaveTemplate> templates = doCloud.getTemplates();
        assertEquals(1, templates.size());

        SlaveTemplate slaveTemplate;

        slaveTemplate = templates.get(0);
        assertEquals(10, slaveTemplate.getIdleTerminationInMinutes());
        assertEquals("72401866", slaveTemplate.getImageId());
        assertEquals(null, slaveTemplate.getInitScript());
        assertEquals(5, slaveTemplate.getInstanceCap());
        assertEquals(Collections.emptySet(), slaveTemplate.getLabelSet());
        assertEquals(null, slaveTemplate.getLabelString());
        assertEquals("", slaveTemplate.getLabels());
        assertEquals("agent", slaveTemplate.getName());
        assertEquals(2, slaveTemplate.getNumExecutors());
        assertEquals("tor1", slaveTemplate.getRegionId());
        assertEquals("s-2vcpu-2gb", slaveTemplate.getSizeId());
        assertEquals(22, slaveTemplate.getSshPort());
        assertEquals(null, slaveTemplate.getTags());
        assertEquals(null, slaveTemplate.getUserData());
        assertEquals("root", slaveTemplate.getUsername());
        assertEquals("/jenkins/", slaveTemplate.getWorkspacePath());
    }

    @Test
    @ConfiguredWithCode("legacy-pre-credentials.yml")
    public void testLegacyPreCredentials() throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        String exported = toYamlString(getJenkinsRoot(context).get("clouds"));

        assertThat(exported, CoreMatchers.containsString("authTokenCredentialId:"));
        assertThat(exported, CoreMatchers.not(CoreMatchers.containsString("authToken:")));

        assertThat(exported, CoreMatchers.containsString("privateKeyCredentialId:"));
        assertThat(exported, CoreMatchers.not(CoreMatchers.containsString("privateKey:")));

        final DigitalOceanCloud doCloud = (DigitalOceanCloud) Jenkins.get().getCloud("mycompany");
        assertNotNull(doCloud);

        final List<SlaveTemplate> templates = doCloud.getTemplates();
        assertEquals(1, templates.size());
        assertNotEquals("", doCloud.getPrivateKeyCredentialId());
        assertEquals("", doCloud.getPrivateKey());
        assertNotNull("", DigitalOceanCloud.getPrivateKeyFromCredentialId(doCloud.getPrivateKeyCredentialId()));
        assertNotEquals("", doCloud.getAuthTokenCredentialId());
        assertEquals("", doCloud.getAuthToken());
        assertNotEquals("", DigitalOceanCloud.getAuthTokenFromCredentialId(doCloud.getAuthTokenCredentialId()));

        SlaveTemplate slaveTemplate;

        slaveTemplate = templates.get(0);
        assertEquals(10, slaveTemplate.getIdleTerminationInMinutes());
        assertEquals("docker-20-04", slaveTemplate.getImageId());
        assertEquals(null, slaveTemplate.getInitScript());
        assertEquals(2, slaveTemplate.getInstanceCap());
        assertEquals(Collections.emptySet(), slaveTemplate.getLabelSet());
        assertEquals(null, slaveTemplate.getLabelString());
        assertEquals("", slaveTemplate.getLabels());
        assertEquals("docker-20-04", slaveTemplate.getName());
        assertEquals(1, slaveTemplate.getNumExecutors());
        assertEquals("tor1", slaveTemplate.getRegionId());
        assertEquals("s-1vcpu-1gb", slaveTemplate.getSizeId());
        assertEquals(22, slaveTemplate.getSshPort());
        assertEquals(null, slaveTemplate.getTags());
        assertEquals(null, slaveTemplate.getUserData());
        assertEquals("root", slaveTemplate.getUsername());
        assertEquals("/jenkins/", slaveTemplate.getWorkspacePath());
    }
}
