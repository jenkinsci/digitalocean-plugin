package com.dubture.jenkins.digitalocean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myjeeva.digitalocean.pojo.Droplet;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;

import hudson.model.TaskListener;
import hudson.slaves.SlaveComputer;

@ExtendWith(MockitoExtension.class)
@WithJenkins
class DigitalOceanComputerLauncherTest {

    public JenkinsRule j;

    @Mock
    private DigitalOceanCloud cl;

    @Mock
    private SlaveTemplate st;

    @Mock
    private DigitalOceanComputer computer;

    @Mock
    private Slave node;

    @Mock
    private TaskListener listener;

    @Mock
    private Connection connection;

    @Mock
    private SCPClient scpClient;

    @Mock
    private Session session;

    private DigitalOceanComputerLauncher launcher;
    private ByteArrayOutputStream outputStream;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.jenkins.clouds.clear();
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(st);

        // when(cl.getDisplayName()).thenReturn("myCloud");
        // when(cl.getTemplates()).thenReturn(templates);
        j.jenkins.clouds.add(cl);

        // Setup output stream for logger
        outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);
        when(listener.getLogger()).thenReturn(printStream);
        // when(node.getNodeName()).thenReturn("nodeName");

        // Setup computer and node
        //when(computer.getNode()).thenReturn(node);
        //when(computer.getName()).thenReturn("test-agent");
        //when(computer.getRemoteAdmin()).thenReturn("test-admin");

        // Setup node properties
        //when(node.getPrivateKey()).thenReturn("test-private-key");
        //when(node.getJvmOpts()).thenReturn("");
        //when(node.getInitScript()).thenReturn(null);

        // Create launcher instance
        launcher = new DigitalOceanComputerLauncher();

        // Mock SSH connection
        //when(connection.authenticateWithPublicKey(anyString(), any(char[].class), anyString())).thenReturn(true);
        //when(connection.createSCPClient()).thenReturn(scpClient);
        //when(connection.openSession()).thenReturn(session);
        //when(connection.exec(anyString(), any(PrintStream.class))).thenReturn(0);
    }

    @Test
    void testLaunchWithNonDigitalOceanComputer() {
        // Given
        SlaveComputer nonDigitalOceanComputer = mock(SlaveComputer.class);

        // When
        launcher.launch(nonDigitalOceanComputer, listener);

        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Cannot handle agent not instance of digital ocean digitalOceanComputer."));
    }

    @Test
    void testLaunchWithDigitalOceanComputer() throws Exception {
        launcher = new DigitalOceanComputerLauncher();

        when(st.provision(any(ProvisioningActivity.Id.class), anyString(), anyString(), anyString(), anyString(), any(Integer.class), anyList(), anyBoolean())).thenReturn(node);
        
        //LabelAtom testingLabel = new LabelAtom("testing");
        //DumbSlave agent = j.createSlave(testingLabel);
        // agent.setLauncher(launcher);

        // j.waitOnline(agent);

        // Given
        DigitalOceanComputer doComputer = new DigitalOceanComputer(node);
        when(doComputer.getNode()).thenReturn(node);

        // When
        launcher.launch(doComputer, listener);

        // Then
        String output = outputStream.toString();
        assertEquals("something", output);
    }

    @Test
    void testLaunchWithNullNode() throws Exception {
        // Given
        when(computer.getNode()).thenReturn(null);

        // When
        launcher.launch(computer, listener);

        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("No real node is available. ABORT"));
    }
}
