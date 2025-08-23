package com.dubture.jenkins.digitalocean;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DigitalOceanComputerLauncherTest {

    @Mock
    private DigitalOceanComputer computer;
    
    @Mock
    private DigitalOceanSlave node;
    
    @Mock
    private TaskListener listener;
    
    @Mock
    private Connection connection;
    
    @Mock
    private SCPClient scpClient;
    
    @Mock
    private Session session;
    
    @Mock
    private Jenkins jenkins;
    
    private DigitalOceanComputerLauncher launcher;
    private ByteArrayOutputStream outputStream;
    
    @BeforeEach
    void setUp() throws Exception {
        // Setup Jenkins instance
        Jenkins.setInstance(jenkins);
        
        // Setup output stream for logger
        outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);
        when(listener.getLogger()).thenReturn(printStream);
        
        // Setup computer and node
        when(computer.getNode()).thenReturn(node);
        when(computer.getName()).thenReturn("test-agent");
        when(computer.getRemoteAdmin()).thenReturn("test-admin");
        
        // Setup node properties
        when(node.getPrivateKey()).thenReturn("test-private-key");
        when(node.getJvmOpts()).thenReturn("");
        when(node.getInitScript()).thenReturn(null);
        
        // Create launcher instance
        launcher = new DigitalOceanComputerLauncher();
        
        // Mock SSH connection
        when(connection.authenticateWithPublicKey(anyString(), any(), anyString())).thenReturn(true);
        when(connection.createSCPClient()).thenReturn(scpClient);
        when(connection.openSession()).thenReturn(session);
        when(connection.exec(anyString(), any(PrintStream.class))).thenReturn(0);
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
    void testLaunchWithNullNode() throws Exception {
        // Given
        when(computer.getNode()).thenReturn(null);
        
        // When
        launcher.launch(computer, listener);
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("No real node is available. ABORT"));
    }
    
    @Test
    void testGetUtcDate() {
        // Given
        Date testDate = new Date(1640995200000L); // 2022-01-01 00:00:00 UTC
        
        // When
        String result = DigitalOceanComputerLauncher.getUtcDate(testDate);
        
        // Then
        assertTrue(result.startsWith("2022-01-01 00:00:00"));
        assertTrue(result.endsWith("UTC"));
    }
    
    @Test
    void testBuildUpCommand() {
        // Given
        when(node.getRemoteAdmin()).thenReturn("test-user");
        
        // When
        String command = launcher.buildUpCommand(computer, "/tmp/script.sh");
        
        // Then
        assertTrue(command.contains("sudo -u test-user /tmp/script.sh"));
    }
    
    @Test
    void testOnClosedCallback() throws Exception {
        // Given
        Channel channel = mock(Channel.class);
        IOException cause = new IOException("Test exception");
        
        // Create a spy to verify the callback behavior
        DigitalOceanComputerLauncher launcherSpy = spy(launcher);
        doReturn(connection).when(launcherSpy).connectToSsh(any(), any());
        
        // Setup session behavior
        when(connection.openSession()).thenReturn(session);
        when(session.getStdin()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(session.getStdout()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(session.getStderr()).thenReturn(new ByteArrayInputStream(new byte[0]));
        
        // When
        launcherSpy.launch(computer, listener);
        
        // Get the callback and trigger it
        ArgumentCaptor<Channel.Listener> listenerCaptor = ArgumentCaptor.forClass(Channel.Listener.class);
        verify(computer).setChannel(any(), any(), any(), listenerCaptor.capture());
        
        Channel.Listener callback = listenerCaptor.getValue();
        callback.onClosed(channel, cause);
        
        // Then
        verify(session).close();
        verify(connection).close();
    }
}
