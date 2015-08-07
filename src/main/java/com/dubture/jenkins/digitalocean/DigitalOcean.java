package com.dubture.jenkins.digitalocean;

import com.google.common.base.Function;
import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Base;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

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
     * are sorted by a key composed of their OS distribution and version, which is useful for display purposes.
     *
     * @param authToken the API authorisation token to use
     * @return a sorted map of {@link Image}s, key on their OS distribution and version
     * @throws DigitalOceanException
     * @throws RequestUnsuccessfulException
     */
    static SortedMap<String,Image> getAvailableImages(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
        DigitalOceanClient client = new DigitalOceanClient(authToken);

        SortedMap<String,Image> availableImages = new TreeMap<String,Image>();
        Images images;
        int page = 0;

        do {
            page += 1;
            images = client.getAvailableImages(page);
            for (Image image : images.getImages()) {
                availableImages.put(image.getDistribution() + " " + image.getName(), image);
            }
        }
        while (images.getMeta().getTotal() > page);

        return availableImages;
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

        return String.format("%d%s / %d%s",
                memory,
                memoryUnits,
                size.getDiskSize(),
                "gb");
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
        LOGGER.log(Level.INFO, "Fetching droplet {0}", dropletId);
        return new DigitalOceanClient(authToken).getDropletInfo(dropletId);
    }
}
