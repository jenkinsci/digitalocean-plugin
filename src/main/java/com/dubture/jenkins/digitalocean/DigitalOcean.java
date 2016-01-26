package com.dubture.jenkins.digitalocean;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.myjeeva.digitalocean.common.ImageType;
import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Droplet;
import com.myjeeva.digitalocean.pojo.Droplets;
import com.myjeeva.digitalocean.pojo.Image;
import com.myjeeva.digitalocean.pojo.Images;
import com.myjeeva.digitalocean.pojo.Key;
import com.myjeeva.digitalocean.pojo.Keys;
import com.myjeeva.digitalocean.pojo.Region;
import com.myjeeva.digitalocean.pojo.Regions;
import com.myjeeva.digitalocean.pojo.Size;
import com.myjeeva.digitalocean.pojo.Sizes;

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
            images = client.getAvailableImages(page);
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
            droplets = apiClient.getAvailableDroplets(page);
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

    static Set<Integer> toBeDestroyedDropletIds = new HashSet<Integer>();

    static void tryDestroyDropletAsync(final String authToken, final int dropletId) {
        // sometimes both Computer and Slave try to destroy the same droplet,
        // which is redundant, so try to prevent that with toBeDestroyedDropletIds
        synchronized (toBeDestroyedDropletIds) {
            if (toBeDestroyedDropletIds.contains(dropletId)) {
                return;
            }
            toBeDestroyedDropletIds.add(dropletId);
        }
        // sometimes droplets have pending events during which you can't send other events.
        // one of such events in spinning up a new droplet, during which a droplet can't be
        // destroyed. so if we receive
        // "com.myjeeva.digitalocean.exception.DigitalOceanException: Droplet already has a pending event."
        // we retry to destroy a droplet.
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                DigitalOceanClient client = new DigitalOceanClient(authToken);
                while (true) {
                    try {
                        client.deleteDroplet(dropletId);
                        break;
                    } catch (Exception e) {
                        if (e.getMessage().contains("pending")) {
                            try {
                                Thread.sleep(10000);
                            } catch (Exception ee) {
                                // ignore
                            }
                            continue;
                        }
                        break;
                    }
                }
                synchronized (toBeDestroyedDropletIds) {
                    toBeDestroyedDropletIds.remove(dropletId);
                }
            }
        });
        t.start();
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
