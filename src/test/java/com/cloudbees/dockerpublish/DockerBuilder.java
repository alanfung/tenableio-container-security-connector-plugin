package com.cloudbees.dockerpublish;

import hudson.tasks.Builder;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;

/**
 * Mock com.cloudbees.dockerpublish.DockerBuilder class for testing.
 */
public class DockerBuilder extends Builder {

    private DockerRegistryEndpoint registryEndpoint;
    private String repoName;
    private String repoTag;

    public DockerBuilder(String registryUrl, String repoName, String repoTag) {
        registryEndpoint = new DockerRegistryEndpoint(registryUrl, null);
        this.repoName = repoName;
        this.repoTag = repoTag;
    }

    public String getRepoName() {
        return repoName;
    }

    public String getRepoTag() {
        return repoTag;
    }

    public DockerRegistryEndpoint getRegistry() {
        return registryEndpoint;
    }

}
