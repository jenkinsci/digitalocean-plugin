package com.dubture.jenkins.digitalocean;

import com.myjeeva.digitalocean.exception.DigitalOceanException;
import com.myjeeva.digitalocean.exception.RequestUnsuccessfulException;
import com.myjeeva.digitalocean.impl.DigitalOceanClient;
import com.myjeeva.digitalocean.pojo.Image;
import com.myjeeva.digitalocean.pojo.Images;
import com.myjeeva.digitalocean.pojo.Region;
import com.myjeeva.digitalocean.pojo.Regions;
import com.myjeeva.digitalocean.pojo.Size;
import com.myjeeva.digitalocean.pojo.Sizes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Various utility methods that make it easier to obtain full lists of properties from Digital Ocean. Some API
 * calls require page number, since the results are paginated, so these utilities will exhaust all the pages and
 * return a single result set.
 *
 * @author Rory Hunter (rory.hunter@blackpepper.co.uk)
 */
public class Utils {

    /**
     * Fetches all available droplet sizes.
     * @param authToken the API authorisation token to use
     * @return a list of {@link Size}s, sorted by their memory capacity
     * @throws DigitalOceanException
     * @throws RequestUnsuccessfulException
     */
    public static List<Size> getAvailableSizes(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
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
    public static SortedMap<String,Image> getAvailableImages(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
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
    public static List<Region> getAvailableRegions(String authToken) throws DigitalOceanException, RequestUnsuccessfulException {
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
    public static String buildSizeLabel(final Size size) {
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
}
