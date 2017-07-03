package com.tenable.io.jenkins.containersecurity;

import org.apache.commons.lang.StringUtils;

class ImageReference {
    String registry;
    String repository;
    String tag;

    static ImageReference Create(String registry, String repositry, String tag) {
        ImageReference pair = new ImageReference();
        pair.registry = registry;
        pair.repository = repositry;
        pair.tag = StringUtils.isBlank(tag) ? "latest" : tag;
        return pair;
    }

    boolean isValid() {
        return !(StringUtils.isBlank(repository) || StringUtils.isBlank(tag));
    }

    String getName() {
        String name = repository + ":" + tag;
        if (registry != null && registry.length() > 0)
            name = registry + "/" + name;
        return name;
    }
}
