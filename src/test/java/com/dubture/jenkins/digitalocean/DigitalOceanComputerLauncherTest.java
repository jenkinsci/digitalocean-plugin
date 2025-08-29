package com.dubture.jenkins.digitalocean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.logging.Logger;

import com.myjeeva.digitalocean.common.DropletStatus;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.Network;
import com.myjeeva.digitalocean.pojo.Networks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;

import hudson.model.TaskListener;
import hudson.slaves.SlaveComputer;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
@WithJenkins
class DigitalOceanComputerLauncherTest {

    public JenkinsRule j;

    @Mock
    private TaskListener listener;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void testLaunchWithNonDigitalOceanComputer() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);
        when(listener.getLogger()).thenReturn(printStream);

        DigitalOceanComputerLauncher launcher = new DigitalOceanComputerLauncher();

        // Given
        SlaveComputer nonDigitalOceanComputer = mock(SlaveComputer.class);

        // When
        launcher.launch(nonDigitalOceanComputer, listener);

        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Cannot handle agent not instance of digital ocean digitalOceanComputer."));
    }


    @Test
    void testLaunchWithNullNode() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);
        when(listener.getLogger()).thenReturn(printStream);

        DigitalOceanComputerLauncher launcher = new DigitalOceanComputerLauncher();

        DigitalOceanComputer computer = mock(DigitalOceanComputer.class);

        // Given
        when(computer.getNode()).thenReturn(null);

        // When
        launcher.launch(computer, listener);

        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("No real node is available. ABORT"));
    }

    ArrayList<String> installJava(final Map<String, Integer> commandResponses) throws Exception {
        ArrayList<String> cmdList = new ArrayList<>();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);
        when(listener.getLogger()).thenReturn(printStream);

        try (MockedStatic<DigitalOcean> digitalOceanStaticClass = Mockito.mockStatic(DigitalOcean.class)) {
            Slave node = mock(Slave.class);
            when(node.getDropletId()).thenReturn(1);
            when(node.getPrivateKey()).thenReturn("private-key");

            DigitalOceanCloud cloud = mock(DigitalOceanCloud.class);
            when(cloud.getTimeoutMinutes()).thenReturn(5);
            when(cloud.getConnectionRetryWait()).thenReturn(5);
            when(cloud.getUsePrivateNetworking()).thenReturn(false);

            Network dropletNetwork = mock(Network.class);
            when(dropletNetwork.getType()).thenReturn("public");
            when(dropletNetwork.getIpAddress()).thenReturn("192.168.10.10");

            Networks dropletNetworks = mock(Networks.class);
            when(dropletNetworks.getVersion4Networks()).thenReturn(List.of(dropletNetwork));

            Droplet droplet = mock(Droplet.class);
            when(droplet.getStatus()).thenReturn(DropletStatus.ACTIVE);
            when(droplet.getNetworks()).thenReturn(dropletNetworks);

            DigitalOceanComputer doComputer = mock(DigitalOceanComputer.class);
            when(doComputer.getNode()).thenReturn(node);
            when(doComputer.getCloud()).thenReturn(cloud);
            when(doComputer.getSshPort()).thenReturn(22);
            when(doComputer.getRemoteAdmin()).thenReturn("root");

            SCPClient scpClient = mock(SCPClient.class);

            Session session = mock(Session.class);
            when(session.getStdin()).thenReturn(new ByteArrayOutputStream());
            when(session.getStdout()).thenReturn(new ByteArrayInputStream("".getBytes()));

            final Connection connection = mock(Connection.class);
            when(connection.authenticateWithPublicKey(anyString(), any(char[].class), anyString())).thenReturn(true);
            when(connection.createSCPClient()).thenReturn(scpClient);
            when(connection.openSession()).thenReturn(session);
            when(connection.exec(anyString(), any(PrintStream.class))).thenAnswer((Answer<Integer>) invocation -> {
                String cmd = invocation.getArgument(0);
                cmdList.add(cmd.trim());
                ((PrintStream) invocation.getArgument(1)).println("Running cmd: " + cmd);

                if (commandResponses.containsKey(cmd.trim())) {
                    return commandResponses.get(cmd.trim());
                }
                return 0;
            });

            // doClient.when(() -> DigitalOceanCloud.getAuthTokenFromCredentialId(any())).thenReturn(authToken);
            // fixme - can be looked into later when we want to confirm the function

            when(doComputer.updateInstanceDescription()).thenReturn(droplet);

            digitalOceanStaticClass.when(() -> DigitalOcean.getDroplet(any(), any())).thenReturn(droplet);

            DigitalOceanComputerLauncher launcher = new DigitalOceanComputerLauncher() {
                @Override
                protected Connection getDropletConnection(String host, int port, PrintStream logger) throws IOException {
                    return connection;
                }
            };
            // Actual run
            launcher.launch(doComputer, listener);

            // Test results
            Logger.getAnonymousLogger().info(outputStream.toString());
        }
        return cmdList;
    }

    @Test
    void testInstallAptJavaRuntimeVersion() throws Exception {
        String runtimeJavaVersion = String.valueOf(Runtime.version().feature());

        ArrayList<String> output = installJava(Map.ofEntries(
            // java is not installed
            Map.entry("java -fullversion", 1),
            // no yum
            Map.entry("which yum", 1),
            // yes apt-get
            Map.entry("which apt-get", 0)
        ));

        assertEquals(List.of(
           "while [ ! -f /var/lib/cloud/instance/boot-finished ]; do echo 'Waiting for cloud-init...'; sleep 1; done",
            "java -fullversion",
            "which yum",
            "which apt-get",
            "apt-get update -q && apt-get install -y openjdk-" + runtimeJavaVersion + "-jre-headless"
        ), output);
    }

    @Test
    void testInstallYumJavaRuntimeVersion() throws Exception {
        String runtimeJavaVersion = String.valueOf(Runtime.version().feature());

        ArrayList<String> output = installJava(Map.ofEntries(
            // java is not installed
            Map.entry("java -fullversion", 1),
            // yes yum
            Map.entry("which yum", 0),
            // no apt-get
            Map.entry("which apt-get", 1)
        ));

        assertEquals(List.of(
            "while [ ! -f /var/lib/cloud/instance/boot-finished ]; do echo 'Waiting for cloud-init...'; sleep 1; done",
            "java -fullversion",
            "which yum",
            "yum install -y java-"+runtimeJavaVersion+"-openjdk-headless"
        ), output);
    }
}
