package com.dubture.jenkins.digitalocean;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DropletName {
    private static final String PREFIX = "jenkins";
    private static final String CLOUD_REGEX = "([a-zA-Z0-9\\.]+)";
    private static final String SLAVE_REGEX = "([a-zA-Z0-9\\.]+)";
    private static final String UUID_REGEX = "\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}";

    private static final String DROPLET_REGEX = PREFIX + "-" + CLOUD_REGEX + "-" + SLAVE_REGEX + "-" + UUID_REGEX;

    private static final Pattern CLOUD_PATTERN = Pattern.compile("^" + CLOUD_REGEX + "$");
    private static final Pattern SLAVE_PATTERN = Pattern.compile("^" + SLAVE_REGEX + "$");
    private static final Pattern DROPLET_PATTERN = Pattern.compile("^" + DROPLET_REGEX + "$");


    private DropletName() {
        throw new AssertionError();
    }

    public static boolean isValidCloudName(final String cloudName) {
        return CLOUD_PATTERN.matcher(cloudName).matches();
    }

    public static boolean isValidSlaveName(final String slaveName) {
        return SLAVE_PATTERN.matcher(slaveName).matches();
    }

    public static String generateDropletName(final String cloudName, final String slaveName) {
        return PREFIX + "-" + cloudName + "-" + slaveName + "-" + UUID.randomUUID().toString();
    }

    public static boolean isDropletInstanceOfCloud(final String dropletName, final String cloudName) {
        Matcher m = DROPLET_PATTERN.matcher(dropletName);
        return m.matches() && m.group(1).equals(cloudName);
    }

    public static boolean isDropletInstanceOfSlave(final String dropletName, final String cloudName, final String slaveName) {
        Matcher m = DROPLET_PATTERN.matcher(dropletName);
        return m.matches() && m.group(1).equals(cloudName) && m.group(2).equals(slaveName);
    }

}
