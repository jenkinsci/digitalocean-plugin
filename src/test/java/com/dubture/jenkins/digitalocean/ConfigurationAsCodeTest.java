package com.dubture.jenkins.digitalocean;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("id:72401866", slaveTemplate.getImageId());
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
        assertEquals("slug:docker-20-04", slaveTemplate.getImageId());
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

    @Test
    @ConfiguredWithCode("post-droplet-name.yml")
    void testHandleDropletIdSlugName(JenkinsConfiguredWithCodeRule j) throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        toYamlString(getJenkinsRoot(context).get("clouds"));

        final DigitalOceanCloud doCloud = (DigitalOceanCloud) Jenkins.get().getCloud("post-droplet-name");
        assertNotNull(doCloud);

        final List<SlaveTemplate> templates = doCloud.getTemplates();

        assertEquals("id:72401866", templates.get(0).getImageId());
        assertEquals(DigitalOcean.ImageBy.ID, templates.get(0).getImageBy());

        assertEquals("slug:docker-20-04", templates.get(1).getImageId());
        assertEquals(DigitalOcean.ImageBy.SLUG, templates.get(1).getImageBy());

        assertEquals("name:this is a droplet name", templates.get(2).getImageId());
        assertEquals(DigitalOcean.ImageBy.NAME, templates.get(2).getImageBy());
    }

    /**
     * Test for issue #101: Provisioning name-based images NPEs when image filters are omitted.
     * <p>
     * When a DigitalOcean cloud template uses a name-based image reference (e.g., "name:img-agent-cicd")
     * and imageFilterPrivate/imageFilterType are omitted from the JCasC configuration,
     * provisioning should NOT fail with an NPE in ImageFilters.getQueryParameters().
     * <p>
     * The fix defaults null filter values to ALL in the ImageFilters constructor.
     *
     * @see <a href="https://github.com/jenkinsci/digitalocean-plugin/issues/101">Issue #101</a>
     */
    @Test
    @ConfiguredWithCode("issue-101-null-image-filters.yml")
    void testIssue101_NameBasedImageWithNullFilters(JenkinsConfiguredWithCodeRule j) {
        final DigitalOceanCloud doCloud = (DigitalOceanCloud) Jenkins.get().getCloud("issue-101-test");
        assertNotNull(doCloud);

        final List<SlaveTemplate> templates = doCloud.getTemplates();
        assertEquals(1, templates.size());

        SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals("name:img-agent-cicd-fra1-do-live", slaveTemplate.getImageId());
        assertEquals(DigitalOcean.ImageBy.NAME, slaveTemplate.getImageBy());

        // These filters are intentionally omitted in the YAML to reproduce issue #101
        assertNull(slaveTemplate.getImageFilterPrivate(), "imageFilterPrivate should be null when omitted from JCasC");
        assertNull(slaveTemplate.getImageFilterType(), "imageFilterType should be null when omitted from JCasC");

        // Creating ImageFilters with null values should NOT throw NPE - nulls default to ALL
        ImageFilters imageFilters = new ImageFilters(
                slaveTemplate.getImageFilterPrivate(),
                slaveTemplate.getImageFilterType(),
                ""
        );

        // Verify that null values defaulted to ALL
        assertEquals(ImageFilters.ImageFilterPrivate.ALL, imageFilters.getPrivateFilter(),
                "null privateFilter should default to ALL");
        assertEquals(ImageFilters.ImageFilterType.ALL, imageFilters.getType(),
                "null type should default to ALL");

        // getQueryParameters() should work without NPE and return empty map (both ALL filters return empty maps)
        assertNotNull(imageFilters.getQueryParameters(),
                "getQueryParameters() should not throw NPE when filters were null");
        assertTrue(imageFilters.getQueryParameters().isEmpty(),
                "getQueryParameters() should return empty map when both filters default to ALL");
    }
}
