package com.dubture.jenkins.digitalocean;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import java.util.List;

import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import jenkins.model.Jenkins;

@WithJenkinsConfiguredWithCode
class ConfigurationAsCodeTest {

    @Test
    @ConfiguredWithCode("CloudEmpty.yml")
    void testEmptyConfig(JenkinsConfiguredWithCodeRule j) {
        final DigitalOceanCloud doCloud = (DigitalOceanCloud) Jenkins.get().getCloud("dojenkins-");
        assertNotNull(doCloud);
        assertEquals(0, doCloud.getTemplates().size());
    }

    @Test
    @ConfiguredWithCode("happy.yml")
    void testHappy(JenkinsConfiguredWithCodeRule j) {
        final DigitalOceanCloud doCloud = (DigitalOceanCloud) Jenkins.get().getCloud("dojenkins-happy");
        assertNotNull(doCloud);

        final List<SlaveTemplate> templates = doCloud.getTemplates();
        assertEquals(1, templates.size());

        SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(10, slaveTemplate.getIdleTerminationInMinutes());
        assertEquals("72401866", slaveTemplate.getImageId());
        assertNull(slaveTemplate.getInitScript());
        assertEquals(5, slaveTemplate.getInstanceCap());
        assertEquals(Collections.emptySet(), slaveTemplate.getLabelSet());
        assertNull(slaveTemplate.getLabelString());
        assertEquals("", slaveTemplate.getLabels());
        assertEquals("agent", slaveTemplate.getName());
        assertEquals(2, slaveTemplate.getNumExecutors());
        assertEquals("tor1", slaveTemplate.getRegionId());
        assertEquals("s-2vcpu-2gb", slaveTemplate.getSizeId());
        assertEquals(22, slaveTemplate.getSshPort());
        assertNull(slaveTemplate.getTags());
        assertNull(slaveTemplate.getUserData());
        assertEquals("root", slaveTemplate.getUsername());
        assertEquals("/jenkins/", slaveTemplate.getWorkspacePath());
    }

    @Test
    @ConfiguredWithCode("legacy-pre-credentials.yml")
    void testLegacyPreCredentials(JenkinsConfiguredWithCodeRule j) throws Exception {
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
        assertNotNull(DigitalOceanCloud.getPrivateKeyFromCredentialId(doCloud.getPrivateKeyCredentialId()), "");
        assertNotEquals("", doCloud.getAuthTokenCredentialId());
        assertEquals("", doCloud.getAuthToken());
        assertNotEquals("", DigitalOceanCloud.getAuthTokenFromCredentialId(doCloud.getAuthTokenCredentialId()));

        SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(10, slaveTemplate.getIdleTerminationInMinutes());
        assertEquals("docker-20-04", slaveTemplate.getImageId());
        assertNull(slaveTemplate.getInitScript());
        assertEquals(2, slaveTemplate.getInstanceCap());
        assertEquals(Collections.emptySet(), slaveTemplate.getLabelSet());
        assertNull(slaveTemplate.getLabelString());
        assertEquals("", slaveTemplate.getLabels());
        assertEquals("docker-20-04", slaveTemplate.getName());
        assertEquals(1, slaveTemplate.getNumExecutors());
        assertEquals("tor1", slaveTemplate.getRegionId());
        assertEquals("s-1vcpu-1gb", slaveTemplate.getSizeId());
        assertEquals(22, slaveTemplate.getSshPort());
        assertNull(slaveTemplate.getTags());
        assertNull(slaveTemplate.getUserData());
        assertEquals("root", slaveTemplate.getUsername());
        assertEquals("/jenkins/", slaveTemplate.getWorkspacePath());
    }
}
