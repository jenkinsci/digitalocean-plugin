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
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * The {@link DigitalOceanComputerLauncher} is responsible for:
 *
 * <ul>
 *   <li>Connecting to a slave via SSH</li>
 *   <li>Installing Java and the Jenkins agent to the slave</li>
 * </ul>
 *
 * @author robert.gruendler@dubture.com
 */
public class DigitalOceanComputerLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(DigitalOceanCloud.class.getName());

    private static abstract class JavaInstaller {
        protected abstract String getInstallCommand(String javaVersion);

        protected abstract String checkPackageManager();

        protected boolean isUsable(Connection conn, PrintStream logger) throws IOException, InterruptedException {
            return checkCommand(conn, logger, checkPackageManager());
        }

        private boolean checkCommand(Connection conn, PrintStream logger, String command) throws IOException, InterruptedException {
            logger.println("Checking: " + command);
            return conn.exec(command, logger) == 0;
        }

        protected int installJava(Connection conn, PrintStream logger) throws IOException, InterruptedException {
            int result = 1;
            for (String version : DigitalOceanComputerLauncher.VALID_VERSIONS) {
                result = conn.exec(getInstallCommand(version), logger);
                if (result == 0) {
                    return result;
                }
            }
            return result;
        }
    }

    private static final List<String> VALID_VERSIONS = Arrays.asList("1.8", "1.7", "1.9");

    private static final Collection<JavaInstaller> INSTALLERS = new HashSet<JavaInstaller>() {{
        add(new JavaInstaller() { // apt
            @Override
            protected String getInstallCommand(String javaVersion) {
                return "apt-get update -q && apt-get install -y " + getPackageName(javaVersion);
            }

            @Override
            protected String checkPackageManager() {
                return "which apt-get";
            }

            private String getPackageName(String javaVersion) {
                return "openjdk-" + javaVersion.replaceFirst("1.", "") + "-jre-headless";
            }
        });
        add(new JavaInstaller() { // yum
            @Override
            protected String getInstallCommand(String javaVersion) {
                return "yum install -y " + getPackageName(javaVersion);
            }

            @Override
            protected String checkPackageManager() {
                return "which yum";
            }

            private String getPackageName(String javaVersion) {
                return "java-" + javaVersion + ".0-openjdk-headless";
            }
        });
    }};

    /**
     * Connects to the given {@link DigitalOceanComputer} via SSH and installs Java/Jenkins agent if necessary.
     */
    @Override
    public void launch(SlaveComputer _computer, TaskListener listener) {

        PrintStream logger = listener.getLogger();

        if(!(_computer instanceof DigitalOceanComputer)) {
            logger.println("Cannot handle slave not instance of digital ocean digitalOceanComputer.");
            return;
        }

        DigitalOceanComputer digitalOceanComputer = (DigitalOceanComputer)_computer;


        Date startDate = new Date();
        logger.println("Start time: " + getUtcDate(startDate));

        final Connection conn;
        Connection cleanupConn = null;
        boolean successful = false;

        Slave node = digitalOceanComputer.getNode();

        if(node == null) {
            logger.println("No real node is available. ABORT");
            return;
        }

        try {
            conn = connectToSsh(digitalOceanComputer, logger);
            cleanupConn = conn;
            logger.println("Authenticating as " + digitalOceanComputer.getRemoteAdmin());
            if (!conn.authenticateWithPublicKey(digitalOceanComputer.getRemoteAdmin(), node.getPrivateKey().toCharArray(), "")) {
                logger.println("Authentication failed");
                throw new Exception("Authentication failed");
            }

            final SCPClient scp = conn.createSCPClient();

            if (!runInitScript(digitalOceanComputer, logger, conn, scp)) {
                LOGGER.severe("Failed to launch: Init script failed to run " + digitalOceanComputer.getName());
                throw new Exception("Init script failed.");
            }

            if (!waitForCloudInit(logger, conn)) {
                LOGGER.severe("Failed to launch: Init script waiting failed " + digitalOceanComputer.getName());
                throw new Exception("Init script waiting failed.");
            }

            if (!installJava(logger, conn)) {
                LOGGER.severe("Failed to launch: java installation failed to run " + digitalOceanComputer.getName());
                throw new Exception("Installing java failed.");
            }

            logger.println("Copying agent.jar");
            scp.put(Jenkins.get().getJnlpJars("agent.jar").readFully(), "agent.jar","/tmp");
            String jvmOpts = Util.fixNull(node.getJvmOpts());
            String launchString = "java " + jvmOpts + " -jar /tmp/agent.jar";
            logger.println("Launching agent agent: " + launchString);
            final Session sess = conn.openSession();
            sess.execCommand(launchString);
            digitalOceanComputer.setChannel(sess.getStdout(), sess.getStdin(), logger, new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    sess.close();
                    conn.close();
                }
            });

            successful = true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
            try {
                Jenkins.getInstance().removeNode(node);
            } catch (Exception ee) {
                ee.printStackTrace(logger);
            }
            e.printStackTrace(logger);
        } finally {
            Date endDate = new Date();
            logger.println("Done setting up at: " + getUtcDate(endDate));
            logger.println("Done in " + TimeUnit.MILLISECONDS.toSeconds(endDate.getTime() - startDate.getTime()) + " seconds");
            if(cleanupConn != null && !successful) {
                cleanupConn.close();
            }
        }
    }

    private boolean runInitScript(final DigitalOceanComputer digitalOceanComputer, final PrintStream logger, final Connection conn, final SCPClient scp)
            throws IOException, InterruptedException {

        Slave node = digitalOceanComputer.getNode();

        if(node == null ) {
            return false;
        }

        String initScript = Util.fixEmptyAndTrim(node.getInitScript());

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
        session.execCommand(buildUpCommand(digitalOceanComputer, "/tmp/init.sh"));

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
        session.execCommand(buildUpCommand(digitalOceanComputer, "touch ~/.hudson-run-init"));
        session.close();

        return true;
    }

    private boolean waitForCloudInit(final PrintStream logger, final Connection conn) throws IOException, InterruptedException {
        logger.println("waiting for cloud init to finish");
        conn.exec("while [ ! -f /var/lib/cloud/instance/boot-finished ]; do echo 'Waiting for cloud-init...'; sleep 1; done", logger);
        logger.println("cloud init is done");
        return true;
    }

    private boolean installJava(final PrintStream logger, final Connection conn) throws IOException, InterruptedException {
        logger.println("Verifying that java exists");
        if (conn.exec("java -fullversion", logger) != 0) {
            logger.println("Try to install one of these Java-versions: " + VALID_VERSIONS);
            //TODO Web UI to let users install a custom java (or any other type of tool) package.
            logger.println("Trying to find a working package manager");
            for (JavaInstaller installer : INSTALLERS) {
                if (!installer.isUsable(conn, logger)) {
                    continue;
                }
                if (installer.installJava(conn, logger) == 0) {
                    return true;
                }
            }

            logger.println("Java could not be installed using any of the supported package managers");
            return false;
        }
        return true;
    }

    private Connection connectToSsh(DigitalOceanComputer digitalOceanComputer, PrintStream logger) throws RequestUnsuccessfulException, DigitalOceanException {

        final long timeout = TimeUnit.MINUTES.toMillis(digitalOceanComputer.getCloud().getTimeoutMinutes());
        final long startTime = System.currentTimeMillis();
        final int sleepTime = digitalOceanComputer.getCloud().getConnectionRetryWait();

        long waitTime;

        while ((waitTime = System.currentTimeMillis() - startTime) < timeout) {

            DigitalOceanCloud cloud = digitalOceanComputer.getCloud();
            Slave node = digitalOceanComputer.getNode();

            if(cloud == null || node == null) {
                logger.println("cloud or node are not available. Waiting for them to come up");
                sleep(sleepTime);
                continue;
            }

            final Droplet droplet;
            try {
                final String authToken = DigitalOceanCloud.getAuthTokenFromCredentialId(cloud.getAuthTokenCredentialId());
                // Hack to fetch this each time through the loop to get the latest information.
                droplet = DigitalOcean.getDroplet(authToken, node.getDropletId());
            } catch (Exception e) {
                logger.println("Failed to get droplet. Retrying");
                sleep(sleepTime);
                continue;
            }

            if (isDropletStarting(droplet)) {
                logger.println("Waiting for droplet to enter ACTIVE state. Sleeping " + sleepTime + " seconds.");
            }
            else {
                try {
                    final String host = getIpAddress(digitalOceanComputer);

                    if (Strings.isNullOrEmpty(host) || "0.0.0.0".equals(host)) {
                        logger.println("No ip address yet, your host is most likely waiting for an ip address.");
                    }
                    else {
                        int port = digitalOceanComputer.getSshPort();

                        Connection conn = getDropletConnection(host, port, logger);
                        if (conn != null) {
                            return conn;
                        }
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
        try {
            conn.connect(null, 10 * 1000, 10 * 1000);
        } catch (SocketTimeoutException e) {
            return null;
        }
        logger.println("Connected via SSH.");
        return conn;
    }

    private static String getIpAddress(DigitalOceanComputer digitalOceanComputer) throws RequestUnsuccessfulException, DigitalOceanException {
        Droplet instance = digitalOceanComputer.updateInstanceDescription();

        final String networkType = digitalOceanComputer.getCloud().getUsePrivateNetworking() ? "private" : "public";

        for (final Network network : instance.getNetworks().getVersion4Networks()) {
            LOGGER.log(Level.INFO, "network {0} => {1}", new Object[] {network.getIpAddress(), network.getType()});
            if (network.getType().equals(networkType)) {
                return network.getIpAddress();
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

    private String buildUpCommand(DigitalOceanComputer digitalOceanComputer, String command) {
//        if (!digitalOceanComputer.getRemoteAdmin().equals("root")) {
//            command = digitalOceanComputer.getRootCommandPrefix() + " " + command;
//        }
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

    private String getUtcDate(Date date) {
        SimpleDateFormat utcFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return utcFormat.format(date);
    }
}
