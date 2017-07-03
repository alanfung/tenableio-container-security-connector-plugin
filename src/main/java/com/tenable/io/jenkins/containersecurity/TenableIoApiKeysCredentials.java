package com.tenable.io.jenkins.containersecurity;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Alan Fung
 */
public class TenableIoApiKeysCredentials extends BaseStandardCredentials {

    private final String accessKey;
    private final String secretKey;

    @DataBoundConstructor
    public TenableIoApiKeysCredentials(
            CredentialsScope scope,
            String id,
            String description,
            String accessKey,
            String secretKey
    ) {
        super(scope, id, description);
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @Override
        public String getDisplayName() {
            return "Tenable IO API Keys";
        }
    }

}
