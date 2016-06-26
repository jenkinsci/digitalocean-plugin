/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Rory Hunter (rory.hunter@blackpepper.co.uk)
 *               2016 Maxim Biro <nurupo.contributions@gmail.com>
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

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.myjeeva.digitalocean.common.ImageType;
import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.*;

import static com.google.common.collect.Lists.newArrayList;

/**
 * Various utility methods that make it easier to obtain full lists of properties from Digital Ocean. Some API
 * calls require page number, since the results are paginated, so these utilities will exhaust all the pages and
 * return a single result set.
 *
 * @author Rory Hunter (rory.hunter@blackpepper.co.uk)
 */
public final class DigitalOcean {

    private DigitalOcean() {
        throw new AssertionError();
    }

    private static final Logger LOGGER = Logger.getLogger(DigitalOcean.class.getName());

    /**
     * Fetches all available droplet sizes.
     * @param authToken the API authorisation token to use
     * @return a list of {@link Size}s, sorted by their memory capacity
     * @throws DigitalOceanException
     * @throws RequestUnsuccessfulException
     */
    static List<Size> getAvailableSizes(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
        DigitalOceanClient client = new DigitalOceanClient(authToken);

        List<Size> availableSizes = new ArrayList<Size>();
        int page = 0;
        Sizes sizes;

        do {
            page += 1;
            sizes = client.getAvailableSizes(page);
            availableSizes.addAll(sizes.getSizes());
        }
        while (sizes.getMeta().getTotal() > page);

        Collections.sort(availableSizes, new Comparator<Size>() {
            @Override
            public int compare(final Size s1, final Size s2) {
                return s1.getMemorySizeInMb().compareTo(s2.getMemorySizeInMb());
            }
        });

        return availableSizes;
    }

    /**
     * Fetches all available images. Unlike the other getAvailable* methods, this returns a map because the values
     * are sorted by a key composed of their OS distribution and version, which is useful for display purposes. Backup
     * images are prefixed with "(Backup) " to easily differentiate them.
     *
     * @param authToken the API authorisation token to use
     * @return a sorted map of {@link Image}s, key on their OS distribution and version
     * @throws DigitalOceanException
     * @throws RequestUnsuccessfulException
     */
    static SortedMap<String,Image> getAvailableImages(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
        DigitalOceanClient client = new DigitalOceanClient(authToken);

        SortedMap<String,Image> availableImages = new TreeMap<String,Image>(ignoringCase());

        Images images;
        int page = 0;

        do {
            page += 1;
            images = client.getAvailableImages(page, Integer.MAX_VALUE);
            for (Image image : images.getImages()) {
                String prefix = getPrefix(image);
                availableImages.put(prefix + image.getDistribution() + " " + image.getName(), image);
            }
        }
        while (images.getMeta().getTotal() > page);

        return availableImages;
    }

	private static String getPrefix(Image image) {

		if (image.getType() == ImageType.BACKUP) {
			return "(Backup) ";
		}

		if (isUserSnapshot(image)) {
			return "(Snapshot) ";
		}

		return "";
	}

	/**
	 * Returns the appropriate identifier for creating droplets from this image.
	 * For non-snapshots, use the image ID instead of the slug (which isn't available
	 * anyway) so that we can build images based upon backups.
	 *
	 * @param image
	 * @return either a "slug" identifier e.g. "ubuntu-15-04-x64", or an integer ID.
	 */
	static String getImageIdentifier(Image image) {
		return image.getType() == ImageType.SNAPSHOT && image.getSlug() != null
			? image.getSlug()
			: image.getId().toString();
	}

	/**
	 * I'm told that if you manually take an image snapshot, then it
	 * has a snapshot type but no slug. Therefore, indicate that it is
	 * a user-snapshot
	 */
	private static boolean isUserSnapshot(Image image) {
		return image.getType() == ImageType.SNAPSHOT && image.getSlug() == null;
	}

	/**
     * Fetches all regions that are flagged as available.
     * @param authToken the API authorisation token to use
     * @return a list of {@link Region}s, sorted by name.
     * @throws DigitalOceanException
     * @throws RequestUnsuccessfulException
     */
    static List<Region> getAvailableRegions(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
        DigitalOceanClient client = new DigitalOceanClient(authToken);

        List<Region> availableRegions = new ArrayList<Region>();
        Regions regions;
        int page = 0;

        do {
            page += 1;
            regions = client.getAvailableRegions(page);
            for (Region region : regions.getRegions()) {
                if (region.isAvailable()) {
                    availableRegions.add(region);
                }
            }
        }
        while (regions.getMeta().getTotal() > page);

        Collections.sort(availableRegions, new Comparator<Region>() {
            @Override
            public int compare(final Region r1, final Region r2) {
                return r1.getName().compareTo(r2.getName());
            }
        });

        return availableRegions;
    }

    /**
     * Constructs a label for the given {@link Size}. Examples:
     * <ul>
     *     <li>"512mb / 20gb"</li>
     *     <li>"1gb / 30gb"</li>
     * </ul>
     * @param size the size to use
     * @return a label with memory and disk size
     */
    static String buildSizeLabel(final Size size) {
        /* It so happens that what we build here is the same as size.getSlug(),
         * but I don't want to rely on that in case it changes.
         */
        int memory = size.getMemorySizeInMb();
        String memoryUnits = "mb";

        if (memory >= 1024) {
            memory = memory / 1024;
            memoryUnits = "gb";
        }

        return String.format("$%s/month ($%s/hour): %d%s RAM, %d CPU, %dgb Disk, %dtb Transfer",
                size.getPriceMonthly().toString(),
                size.getPriceHourly().toString(),
                memory,
                memoryUnits,
                size.getVirutalCpuCount(),
                size.getDiskSize(),
                size.getTransfer());
    }

    static List<Key> getAvailableKeys(String authToken) throws RequestUnsuccessfulException, DigitalOceanException {

        DigitalOceanClient client = new DigitalOceanClient(authToken);
        List<Key> availableKeys = new ArrayList<Key>();

        Keys keys;
        int page = 1;

        do {
            keys = client.getAvailableKeys(page);
            availableKeys.addAll(keys.getKeys());
            page += 1;
        }
        while (keys.getMeta().getTotal() > page);

        return availableKeys;
    }

    /**
     * Fetches a list of all available droplets. The implementation will fetch all pages and return a single list
     * of droplets.
     * @return a list of all available droplets.
     * @throws DigitalOceanException
     * @throws RequestUnsuccessfulException
     */
    static List<Droplet> getDroplets(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
        LOGGER.log(Level.INFO, "Listing all droplets");
        DigitalOceanClient apiClient = new DigitalOceanClient(authToken);
        List<Droplet> availableDroplets = newArrayList();
        Droplets droplets;
        int page = 1;

        do {
            droplets = apiClient.getAvailableDroplets(page, Integer.MAX_VALUE);
            availableDroplets.addAll(droplets.getDroplets());
            page += 1;
        }
        while (droplets.getMeta().getTotal() > page);

        return availableDroplets;
    }

    /**
     * Fetches information for the specified droplet.
     * @param authToken the API authentication token to use
     * @param dropletId the ID of the droplet to query
     * @return information for the specified droplet
     * @throws DigitalOceanException
     * @throws RequestUnsuccessfulException
     */
    static Droplet getDroplet(String authToken, Integer dropletId) throws DigitalOceanException, RequestUnsuccessfulException {
        LOGGER.log(Level.INFO, "Fetching droplet " + dropletId);
        return new DigitalOceanClient(authToken).getDropletInfo(dropletId);
    }

    static Image newImage(String idOrSlug) {
        Image image;

        try {
            image = new Image(Integer.parseInt(idOrSlug));
        }
        catch (NumberFormatException e) {
            image = new Image(idOrSlug);
        }

        return image;
    }

    private static class DestroyInfo {
        public final String authToken;
        public final int dropletId;

        public DestroyInfo(String authToken, int dropletId) {
            this.authToken = authToken;
            this.dropletId = dropletId;
        }
    }

    private static final List<DestroyInfo> toBeDestroyedDroplets = new ArrayList<DestroyInfo>();

    // sometimes droplets have pending events during which you can't destroy them.
    // one of such events in spinning up a new droplet. so we continiously try to
    // destroy droplets in a separate thread
    private static final Thread dropletDestroyer = new Thread(new Runnable() {
        @Override
        public void run() {

            do {
                String previousAuthToken = null;
                DigitalOceanClient client = null;
                List<Droplet> droplets = null;
                boolean failedToDestroy = false;

                synchronized (toBeDestroyedDroplets) {
                    Iterator<DestroyInfo> it = toBeDestroyedDroplets.iterator();
                    while (it.hasNext()) {
                        DestroyInfo di = it.next();

                        // the list should be sorted by di.authToken to prevent unnecessary DigitalOceanClient recreation
                        if (di.authToken != previousAuthToken) {
                            previousAuthToken = di.authToken;
                            client = new DigitalOceanClient(di.authToken);
                            // new auth token -- new list of droplets
                            droplets = null;
                        }

                        try {
                            LOGGER.info("Trying to destroy droplet " + di.dropletId);
                            client.deleteDroplet(di.dropletId);
                            LOGGER.info("Droplet " + di.dropletId + " is destroyed");
                            it.remove();
                        } catch (Exception e) {
                            // check if such droplet even exist in the first place
                            if (droplets == null) {
                                try {
                                    droplets = client.getAvailableDroplets(1, Integer.MAX_VALUE).getDroplets();
                                } catch (Exception ee) {
                                    // ignore
                                }
                            }
                            if (droplets != null) {
                                boolean found = false;
                                for (Droplet d : droplets) {
                                    if (d.getId() == di.dropletId) {
                                        found = true;
                                        break;
                                    }
                                }

                                if (!found) {
                                    // such droplet doesn't exist, stop trying to destroy it you dummy
                                    LOGGER.info("Droplet " + di.dropletId + " doesn't even exist, stop trying to destroy it you dummy!");
                                    it.remove();
                                    continue;
                                }
                            }
                            // such droplet might exist, so let's retry later
                            failedToDestroy = true;
                            LOGGER.warning("Failed to destroy droplet " + di.dropletId);
                            LOGGER.log(Level.WARNING, e.getMessage(), e);
                        }
                    }

                    if (failedToDestroy) {
                        LOGGER.info("Retrying to destroy the droplets in about 10 seconds");
                        try {
                            // sleep for 10 seconds, but wake up earlier if notified
                            toBeDestroyedDroplets.wait(10000);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                    } else {
                        LOGGER.info("Waiting on more droplets to destroy");
                        while (toBeDestroyedDroplets.isEmpty()) {
                            try {
                                toBeDestroyedDroplets.wait();
                            } catch (InterruptedException e) {
                                // ignore
                            }
                        }
                    }
                }
            } while (true);
        }
    });

    static void tryDestroyDropletAsync(final String authToken, final int dropletId) {
        synchronized (toBeDestroyedDroplets) {
            LOGGER.info("Adding droplet to destroy " + dropletId);

            toBeDestroyedDroplets.add(new DestroyInfo(authToken, dropletId));

            // sort by authToken
            Collections.sort(toBeDestroyedDroplets, new Comparator<DestroyInfo>() {
                @Override
                public int compare(DestroyInfo o1, DestroyInfo o2) {
                    return o1.authToken.compareTo(o2.authToken);
                }
            });

            toBeDestroyedDroplets.notifyAll();

            if (!dropletDestroyer.isAlive()) {
                dropletDestroyer.start();
            }
        }
    }

    private static Comparator<String> ignoringCase() {
        return new Comparator<String>() {
            @Override
            public int compare(final String o1, final String o2) {
                return o1.compareToIgnoreCase(o2);
            }
        };
    }
}
