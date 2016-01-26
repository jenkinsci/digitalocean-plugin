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
import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.Network;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.SlaveComputer;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.PrintStream;

import static java.lang.String.format;

/**
 * The {@link ComputerLauncher} is responsible for:
 *
 * <ul>
 *   <li>Connecting to a slave via SSH</li>
 *   <li>Installing Java and the Jenkins agent to the slave</li>
 * </ul>
 *
 * @author robert.gruendler@dubture.com
 */
public class ComputerLauncher extends hudson.slaves.ComputerLauncher {

    private enum BootstrapResult {
        FAILED,
        SAMEUSER,
        RECONNECT
    }

    /**
     * Connects to the given {@link Computer} via SSH and installs Java/Jenkins agent if necessary.
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

            switch (bootstrap(bootstrapConn, computer, logger)) {
                case FAILED:
                    logger.println("bootstrap failed");
                    listener.fatalError("bootstrap failed");
                    return; // bootstrap closed for us.

                case SAMEUSER:
                    cleanupConn = bootstrapConn; // take over the connection
                    logger.println("take over connection");

                case RECONNECT:// connect fresh as ROOT
                    logger.println("connect fresh as root");
                    cleanupConn = connectToSsh(computer, logger);

                    if (!cleanupConn.authenticateWithPublicKey(computer.getRemoteAdmin(), computer.getNode().getPrivateKey().toCharArray(), "")) {
                        logger.println("Authentication failed");
                        return; // failed to connect as root.
                    }
            }

            conn = cleanupConn;
            final SCPClient scp = conn.createSCPClient();

            if (!runInitScript(computer, logger, conn, scp)) {
                return;
            }

            if (!installJava(logger, conn)) {
                return;
            }

            logger.println("Copying slave.jar");
            scp.put(Jenkins.getInstance().getJnlpJars("slave.jar").readFully(), "slave.jar","/tmp");
            String jvmOpts = Util.fixNull(computer.getNode().jvmOpts);
            String launchString = "java " + jvmOpts + " -jar /tmp/slave.jar";
            logger.println("Launching slave agent: " + launchString);
            final Session sess = conn.openSession();
            sess.execCommand(launchString);
            computer.setChannel(sess.getStdout(), sess.getStdin(), logger, new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    sess.close();
                    conn.close();
                }
            });

            successful = true;
        } catch (Exception e) {
            try {
                Jenkins.getInstance().removeNode(computer.getNode());
            } catch (Exception ee) {
                ee.printStackTrace(logger);
            }
            e.printStackTrace(logger);
        } finally {
            if(cleanupConn != null && !successful) {
                cleanupConn.close();
            }
        }
    }

    private boolean runInitScript(final Computer computer, final PrintStream logger, final Connection conn, final SCPClient scp)
            throws IOException, InterruptedException {

        String initScript = Util.fixEmptyAndTrim(computer.getNode().getInitScript());

        if (initScript == null) {
            return true;
        }
        if (conn.exec("test -e ~/.hudson-run-init", logger) == 0) {
            return true;
        }

        logger.println("Executing init script");
        scp.put(initScript.getBytes("UTF-8"), "init.sh", "/tmp", "0700");
        Session session = conn.openSession();
        session.requestDumbPTY(); // so that the remote side bundles stdout and stderr
        session.execCommand(buildUpCommand(computer, "/tmp/init.sh"));

        session.getStdin().close();    // nothing to write here
        session.getStderr().close();   // we are not supposed to get anything from stderr
        IOUtils.copy(session.getStdout(), logger);

        int exitStatus = waitCompletion(session);
        if (exitStatus != 0) {
            logger.println("init script failed: exit code=" + exitStatus);
            return false;
        }
        session.close();

        // Needs a tty to run sudo.
        session = conn.openSession();
        session.requestDumbPTY(); // so that the remote side bundles stdout and stderr
        session.execCommand(buildUpCommand(computer, "touch ~/.hudson-run-init"));
        session.close();

        return true;
    }

    private boolean installJava(final PrintStream logger, final Connection conn) throws IOException, InterruptedException {
        logger.println("Verifying that java exists");

        if (conn.exec("java -fullversion", logger) !=0) {
            logger.println("Installing Java");

            // TODO: Add support for non-debian based java installations
            // and let the user select the java version
            if (conn.exec("apt-get update -q && apt-get install -y openjdk-7-jdk", logger) !=0) {
                logger.println("Failed to download Java");
                return false;
            }
        }
        return true;
    }

    private BootstrapResult bootstrap(Connection bootstrapConn, Computer computer, PrintStream logger) throws IOException, InterruptedException {
        logger.println("bootstrap()" );
        boolean closeBootstrap = true;

        if (bootstrapConn.isAuthenticationComplete()) {
            return BootstrapResult.SAMEUSER;
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
                return BootstrapResult.FAILED;
            }
            closeBootstrap = false;
            return BootstrapResult.SAMEUSER;
        } catch (Exception e) {
            e.printStackTrace(logger);
            return BootstrapResult.FAILED;
        } finally {
            if (closeBootstrap)
                bootstrapConn.close();
        }
    }

    private Connection connectToSsh(Computer computer, PrintStream logger) throws RequestUnsuccessfulException, DigitalOceanException {

        // TODO: make configurable?
        final long timeout = TimeUnit2.MINUTES.toMillis(5);
        final long startTime = System.currentTimeMillis();
        final int sleepTime = 10;

        long waitTime;

        while ((waitTime = System.currentTimeMillis() - startTime) < timeout) {

            // Hack to fetch this each time through the loop to get the latest information.
            final Droplet droplet = DigitalOcean.getDroplet(
                    computer.getCloud().getAuthToken(),
                    computer.getNode().getDropletId());

            if (isDropletStarting(droplet)) {
                logger.println("Waiting for droplet to enter ACTIVE state. Sleeping " + sleepTime + " seconds.");
            }
            else {
                try {
                    final String host = getIpAddress(computer);

                    if (Strings.isNullOrEmpty(host) || "0.0.0.0".equals(host)) {
                        logger.println("No ip address yet, your host is most likely waiting for an ip address.");
                    }
                    else {
                        int port = computer.getSshPort();

                        return getDropletConnection(host, port, logger);
                    }
                } catch (IOException e) {
                    // Ignore, we'll retry.
                }
                logger.println("Waiting for SSH to come up. Sleeping " + sleepTime + " seconds.");
            }

            sleep(sleepTime);
        }

        throw new RuntimeException(format(
            "Timed out after %d seconds of waiting for ssh to become available (max timeout configured is %s)",
            waitTime / 1000,
            timeout / 1000));
    }

    private static boolean isDropletStarting(final Droplet droplet) {

        switch (droplet.getStatus()) {
            case NEW:
                return true;

            case ACTIVE:
                return false;

            default:
                throw new IllegalStateException("Droplet has unexpected status: " + droplet.getStatus());
        }
    }

    private Connection getDropletConnection(String host, int port, PrintStream logger) throws IOException {
        logger.println("Connecting to " + host + " on port " + port + ". ");
        Connection conn = new Connection(host, port);
        conn.connect();
        logger.println("Connected via SSH.");
        return conn;
    }

    private static String getIpAddress(Computer computer) throws RequestUnsuccessfulException, DigitalOceanException {
        Droplet instance = computer.updateInstanceDescription();

        for (final Network network : instance.getNetworks().getVersion4Networks()) {
            String host = network.getIpAddress();
            if (host != null) {
                return host;
            }
        }

        return null;
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

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000);
        }
        catch (InterruptedException e) {
            // Ignore
        }
    }
}
