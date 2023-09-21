/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Rory Hunter (rory.hunter@blackpepper.co.uk)
 *               2016 Maxim Biro <nurupo.contributions@gmail.com>
 *               2017, 2021 Harald Sitter <sitter@kde.org>
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

import java.text.MessageFormat;
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

        List<Size> availableSizes = new ArrayList<>();
        int page = 0;
        Sizes sizes;

        do {
            page += 1;
            sizes = client.getAvailableSizes(page);
            availableSizes.addAll(sizes.getSizes());
        }
        while (sizes.getMeta().getTotal() > availableSizes.size());

        availableSizes.sort(Comparator.comparing(Size::getMemorySizeInMb));

        return availableSizes;
    }

    static enum ImageFilter
    {
        ALLIMAGES,
        USERIMAGES
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
    static SortedMap<String,Image> getAvailableImages(String authToken, ImageFilter filter) throws DigitalOceanException, RequestUnsuccessfulException {
        DigitalOceanClient client = new DigitalOceanClient(authToken);

        SortedMap<String,Image> availableImages = new TreeMap<>(ignoringCase());

        Images images = null;
        int page = 0;

        do {
            page += 1;
            switch (filter) {
                case ALLIMAGES:
                    images = client.getAvailableImages(page, Integer.MAX_VALUE);
                    break;
                case USERIMAGES:
                    images = client.getUserImages(page, Integer.MAX_VALUE);
                    break;
            }
            for (Image image : images.getImages()) {
                String prefix = getPrefix(image);
                final String name = prefix + image.getDistribution() + " " + image.getName();
                String numberedName = name;
                int count = 2;
                while (availableImages.containsKey(numberedName)) {
                    numberedName = name + " (" + count + ")";
                    count ++;
                }
                availableImages.put(numberedName, image);
            }
        }
        while (images.getMeta().getTotal() > availableImages.size());

        return availableImages;
    }

    static SortedMap<String,Image> getAvailableImages(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
        return getAvailableImages(authToken, ImageFilter.ALLIMAGES);
    }

    static SortedMap<String,Image> getAvailableUserImages(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
        return getAvailableImages(authToken, ImageFilter.USERIMAGES);
    }

	private static String getPrefix(Image image) {

		if (image.getType() == ImageType.BACKUP) {
			return "(Backup) ";
		}

		if (image.getType() == ImageType.SNAPSHOT && image.getSlug() == null && !image.isAvailablePublic()) {
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
     * Fetches all regions that are flagged as available.
     * @param authToken the API authorisation token to use
     * @return a list of {@link Region}s, sorted by name.
     * @throws DigitalOceanException
     * @throws RequestUnsuccessfulException
     */
    static List<Region> getAvailableRegions(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
        DigitalOceanClient client = new DigitalOceanClient(authToken);

        List<Region> availableRegions = new ArrayList<>();
        Regions regions;
        int page = 0;

        do {
            page += 1;
            regions = client.getAvailableRegions(page);
            availableRegions.addAll(regions.getRegions());
        }
        while (regions.getMeta().getTotal() > availableRegions.size());

        availableRegions.sort(Comparator.comparing(Region::getName));

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

        final String slug = size.getSlug();
        String type = "Regular";
        if (slug.startsWith("c-")) {
            type = "High CPU";
        } else if (slug.startsWith("m-")) {
            type = "High Memory";
        }

        return String.format("$%s/month ($%s/hour): %d%s RAM, %d CPU, %dgb Disk, %.2ftb Transfer (%s)",
                size.getPriceMonthly().toString(),
                size.getPriceHourly().toString(),
                memory,
                memoryUnits,
                size.getVirutalCpuCount(),
                size.getDiskSize(),
                size.getTransfer(),
                type
        );
    }

    static List<Key> getAvailableKeys(String authToken) throws RequestUnsuccessfulException, DigitalOceanException {

        DigitalOceanClient client = new DigitalOceanClient(authToken);
        List<Key> availableKeys = new ArrayList<>();

        Keys keys;
        int page = 0;

        do {
            page += 1;
            keys = client.getAvailableKeys(page);
            availableKeys.addAll(keys.getKeys());
        }
        while (keys.getMeta().getTotal() > availableKeys.size());

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
        int page = 0;

        do {
            page += 1;
            droplets = apiClient.getAvailableDroplets(page, Integer.MAX_VALUE);
            availableDroplets.addAll(droplets.getDroplets());
        }
        while (droplets.getMeta().getTotal() > availableDroplets.size());

        return availableDroplets;
    }

    static Image getMatchingNamedImage(String authToken, String imageName) throws DigitalOceanException, RequestUnsuccessfulException {
        List<Image> matchingImages = new ArrayList<Image>();

        final SortedMap<String, Image> images = getAvailableUserImages(authToken);
        for (Image image : images.values()) {
            if (imageName.equals(image.getName())) {
                matchingImages.add(image);
            }
        }

        Collections.sort(matchingImages, new Comparator<Image>() {
            @Override
            public int compare(Image left, Image right) {
                return left.getCreatedDate().compareTo(right.getCreatedDate());
            }
        });

        if (matchingImages.size() < 1) {
            throw new RuntimeException(MessageFormat.format("Failed to resolve image name '{0}'", imageName));
        }

        return matchingImages.get(0);
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

    static Image newImage(String authToken, String idOrSlugOrName, Boolean imageByName) throws DigitalOceanException, RequestUnsuccessfulException {
        if (imageByName) {
            return getMatchingNamedImage(authToken, idOrSlugOrName);
        }

        Image image;
        try {
            image = new Image(Integer.parseInt(idOrSlugOrName));
        }
        catch (NumberFormatException e) {
            image = new Image(idOrSlugOrName);
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

    private static final List<DestroyInfo> toBeDestroyedDroplets = new ArrayList<>();

    // sometimes droplets have pending events during which you can't destroy them.
    // one of such events in spinning up a new droplet. so we continuously try to
    // destroy droplets in a separate thread
    private static final Thread dropletDestroyer = new Thread(() -> {

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
                    if (!di.authToken.equals(previousAuthToken)) {
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
    });

    static void tryDestroyDropletAsync(final String authToken, final int dropletId) {
        synchronized (toBeDestroyedDroplets) {
            LOGGER.info("Adding droplet to destroy " + dropletId);

            toBeDestroyedDroplets.add(new DestroyInfo(authToken, dropletId));

            // sort by authToken
            toBeDestroyedDroplets.sort(Comparator.comparing(o -> o.authToken));

            toBeDestroyedDroplets.notifyAll();

            if (!dropletDestroyer.isAlive()) {
                dropletDestroyer.start();
            }
        }
    }

    private static Comparator<String> ignoringCase() {
        return String::compareToIgnoreCase;
    }
}
