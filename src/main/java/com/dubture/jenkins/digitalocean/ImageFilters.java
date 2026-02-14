package com.dubture.jenkins.digitalocean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean
public class ImageFilters implements Serializable {
    private static final long serialVersionUID = 1L;

    public interface ImageFilterInterface {
        public Map<String, String> getQueryParameters();

        public String getLabel();
    }

    public static enum ImageFilterPrivate implements ImageFilterInterface {
        ALL {
            @Override
            public Map<String, String> getQueryParameters() {
                return Collections.emptyMap();
            }

            @Override
            public String getLabel() {
                return "All Images";
            }
        },

        PRIVATE {
            @Override
            public Map<String, String> getQueryParameters() {
                HashMap<String, String> qs = new HashMap<>();
                qs.put("private", "true");
                return qs;
            }

            @Override
            public String getLabel() {
                return "Custom Images";
            }
        };
    }

    public static enum ImageFilterType implements ImageFilterInterface {
        ALL {
            @Override
            public Map<String, String> getQueryParameters() {
                return Collections.emptyMap();
            }

            @Override
            public String getLabel() {
                return "All";
            }
        },
        APPLICATION {
            @Override
            public Map<String, String> getQueryParameters() {
                HashMap<String, String> qs = new HashMap<>();
                qs.put("type", "application");
                return qs;
            }

            @Override
            public String getLabel() {
                return "Applications";
            }
        },
        DISTRIBUTION {
            @Override
            public Map<String, String> getQueryParameters() {
                HashMap<String, String> qs = new HashMap<>();
                qs.put("type", "distribution");
                return qs;
            }

            @Override
            public String getLabel() {
                return "Distribution";
            }
        };
    }

    final public ImageFilterPrivate privateFilter;

    public ImageFilterPrivate getPrivateFilter() {
        return privateFilter;
    }

    final public ImageFilterType type;

    public ImageFilterType getType() {
        return type;
    }

    final public ArrayList<String> tags;

    public ArrayList<String> getTags() {
        return tags;
    }

    public ImageFilters(ImageFilterPrivate privateFilter, ImageFilterType type,
            String tags) {
        this.privateFilter = privateFilter;
        this.type = type;
        this.tags = new ArrayList<>();
        if (tags != null && !tags.trim().isEmpty()) {
            for (String tag : tags.split(",")) {
                this.tags.add(tag.trim());
            }
        }
    }

    public Map<String, String> getQueryParameters() {
        HashMap<String, String> qs = new HashMap<>();
        qs.putAll(this.privateFilter.getQueryParameters());
        qs.putAll(this.type.getQueryParameters());
        // FIXME - tags
        return qs;
    }
}
