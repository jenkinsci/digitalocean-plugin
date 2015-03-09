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

import com.google.common.base.Strings;
import com.myjeeva.digitalocean.exception.AccessDeniedException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.exception.ResourceNotFoundException;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;
import hudson.model.Hudson;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.SlaveComputer;
import hudson.util.TimeUnit2;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.PrintStream;

/**
 *
 * The {@link com.dubture.jenkins.digitalocean.ComputerLauncher} is responsible for
 *
 * - connecting to a slave via SSH
 * - installing Java and the Jenkins agent o the slave
 *
 * @author robert.gruendler@dubture.com
 */
public class ComputerLauncher extends hudson.slaves.ComputerLauncher {

    private final int FAILED=-1;

    private final int SAMEUSER=0;

    private final int RECONNECT=-2;

    /**
     * Connects to the given {@link com.dubture.jenkins.digitalocean.Computer} via SSH and installs Java/Jenkins agent if necessary.
     * @param _computer
     * @param listener
     */
    @Override
    public void launch(SlaveComputer _computer, TaskListener listener) {

        Computer computer = (Computer)_computer;
        PrintStream logger = listener.getLogger();

        final Connection bootstrapConn;
        final Connection conn;
        Connection cleanupConn = null;
        boolean successful = false;

        try {
            bootstrapConn = connectToSsh(computer, logger);
            int bootstrapResult = bootstrap(bootstrapConn, computer, logger);
            if (bootstrapResult == FAILED) {
                logger.println("bootstrapresult failed");
                listener.fatalError("bootstrapresult failed");
                return; // bootstrap closed for us.
            }
            else if (bootstrapResult == SAMEUSER) {
                cleanupConn = bootstrapConn; // take over the connection
                logger.println("take over connection");
            }
            else {
                // connect fresh as ROOT
                logger.println("connect fresh as root");
                cleanupConn = connectToSsh(computer, logger);

                if (!cleanupConn.authenticateWithPublicKey(computer.getRemoteAdmin(), computer.getNode().getPrivateKey().toCharArray(), "")) {
                    logger.println("Authentication failed");
                    return; // failed to connect as root.
                }
            }
            conn = cleanupConn;

            SCPClient scp = conn.createSCPClient();
            String initScript = computer.getNode().initScript;

            if(initScript != null && initScript.trim().length() > 0 && conn.exec("test -e ~/.hudson-run-init", logger) != 0) {
                logger.println("Executing init script");
                scp.put(initScript.getBytes("UTF-8"),"init.sh","/tmp","0700");
                Session sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(buildUpCommand(computer, "/tmp/init.sh"));

                sess.getStdin().close();    // nothing to write here
                sess.getStderr().close();   // we are not supposed to get anything from stderr
                IOUtils.copy(sess.getStdout(), logger);

                int exitStatus = waitCompletion(sess);
                if (exitStatus!=0) {
                    logger.println("init script failed: exit code="+exitStatus);
                    return;
                }
                sess.close();

                // Needs a tty to run sudo.
                sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(buildUpCommand(computer, "touch ~/.hudson-run-init"));
                sess.close();
            }

            logger.println("Verifying that java exists");
            if(conn.exec("java -fullversion", logger) !=0) {
                logger.println("Installing Java");

                // TODO: Add support for non-debian based java installations
                // and let the user select the java version
                if(conn.exec("apt-get update -q && apt-get install -y openjdk-7-jdk", logger) !=0) {
                    logger.println("Failed to download Java");
                    return;
                }
            }

            logger.println("Copying slave.jar");
            scp.put(Hudson.getInstance().getJnlpJars("slave.jar").readFully(), "slave.jar","/tmp");
            String jvmopts = computer.getNode().jvmopts;
            String launchString = "java " + (jvmopts != null ? jvmopts : "") + " -jar /tmp/slave.jar";
            logger.println("Launching slave agent: " + launchString);
            final Session sess = conn.openSession();
            sess.execCommand(launchString);
            computer.setChannel(sess.getStdout(),sess.getStdin(),logger,new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    sess.close();
                    conn.close();
                }
            });

            successful = true;
        } catch (Exception e) {
            e.printStackTrace(logger);
        } finally {
            if(cleanupConn != null && !successful) {
                cleanupConn.close();
            }
        }
    }


    private int bootstrap(Connection bootstrapConn, Computer computer, PrintStream logger) throws IOException, InterruptedException {
        logger.println("bootstrap()" );
        boolean closeBootstrap = true;

        if (bootstrapConn.isAuthenticationComplete()) {
            return SAMEUSER;
        }

        try {
            int tries = 20;
            boolean isAuthenticated = false;

            while (tries-- > 0) {
                logger.println("Authenticating as " + computer.getRemoteAdmin());

                isAuthenticated = bootstrapConn.authenticateWithPublicKey(computer.getRemoteAdmin(), computer.getNode().getPrivateKey().toCharArray(), "");
                if (isAuthenticated) {
                    break;
                }
                logger.println("Authentication failed. Trying again...");
                Thread.sleep(10000);
            }
            if (!isAuthenticated) {
                logger.println("Authentication failed");
                return FAILED;
            }
            closeBootstrap = false;
            return SAMEUSER;
        } catch (Exception e) {
            e.printStackTrace(logger);
            return FAILED;
        } finally {
            if (closeBootstrap)
                bootstrapConn.close();
        }
    }

    private Connection connectToSsh(Computer computer, PrintStream logger) throws InterruptedException, RequestUnsuccessfulException, AccessDeniedException, ResourceNotFoundException {

        // TODO: make configurable?
        final long timeout = TimeUnit2.MINUTES.toMillis(5);
        final long startTime = System.currentTimeMillis();

        while(true) {
            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if ( waitTime > timeout ) {
                    throw new RuntimeException("Timed out after "+ (waitTime / 1000) + " seconds of waiting for ssh to become available. (maximum timeout configured is "+ (timeout / 1000) + ")" );
                }
                Droplet instance = computer.updateInstanceDescription();
                String host = instance.getIpAddress();

                if (Strings.isNullOrEmpty(host) || "0.0.0.0".equals(host)) {
                    logger.println("No ip address yet, your host is most likely waiting for an ip address.");
                    throw new IOException("sleep");
                }

                int port = computer.getSshPort();
                logger.println("Connecting to " + host + " on port " + port + ". ");
                Connection conn = new Connection(host, port);
                conn.connect();
                logger.println("Connected via SSH.");
                return conn; // successfully connected
            } catch (IOException e) {
                // keep retrying until SSH comes up
                logger.println("Waiting for SSH to come up. Sleeping 5 seconds.");
                Thread.sleep(5000);
            }
        }
    }

    private int waitCompletion(Session session) throws InterruptedException {
        // I noticed that the exit status delivery often gets delayed. Wait up to 1 sec.
        for( int i=0; i<10; i++ ) {
            Integer r = session.getExitStatus();
            if(r!=null) return r;
            Thread.sleep(100);
        }
        return -1;
    }

    protected String buildUpCommand(Computer computer, String command) {
        if (!computer.getRemoteAdmin().equals("root")) {
//            command = computer.getRootCommandPrefix() + " " + command;
        }
        return command;
    }
}
