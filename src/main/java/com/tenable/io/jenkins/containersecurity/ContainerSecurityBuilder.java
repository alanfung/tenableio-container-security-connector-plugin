package com.tenable.io.jenkins.containersecurity;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * @author Alan Fung
 */
public class ContainerSecurityBuilder extends Builder {

    private static final String CLOUDBEES_BUILDER_FQCN = "com.cloudbees.dockerpublish.DockerBuilder";
    private static final String CLOUDBEES_BUILDER_GET_REPO_NAME = "getRepoName";
    private static final String CLOUDBEES_BUILDER_GET_REPO_TAG = "getRepoTag";
    private static final String CLOUDBEES_BUILDER_GET_REGISTRY = "getRegistry";

    static final String ERROR_NO_CLOUDBEES =
            "Unable to detect any preceding CloudBees Docker Build and Publish build step.";
    static final String ERROR_NO_VALID_IMAGE = "No valid image specified for scanning.";

    private static final String HEADER_API_KEYS = "accessKey=%s;secretKey=%s;";
    private static final String URI_IMAGE_COMPLIANCE_SERVICE =
            "https://cloud.tenable.com/container-security/api/v1/policycompliance?image_id=%s";

    private static final int MAX_RETRIES = 30;
    private static final int POLL_INTERVAL = 5000;
    private static final String TENABLEIO_REGISTRY = "registry.cloud.tenable.com";

    private final String apiKeysCredentialsId;
    private final boolean detectCloudBees;
    private final String pushRepository;
    private final String pushTag;
    private final String repository;
    private final String tag;

    @DataBoundConstructor
    public ContainerSecurityBuilder(
            String apiKeysCredentialsId,
            boolean detectCloudBees, // Note: this value is negated in the UI for UX reasons.
            String pushRepository,
            String pushTag,
            String repository,
            String tag) {
        this.apiKeysCredentialsId = apiKeysCredentialsId;
        this.detectCloudBees = !detectCloudBees;
        this.pushRepository = pushRepository;
        this.pushTag = pushTag;
        this.repository = repository;
        this.tag = tag;
    }

    public String getApiKeysCredentialsId() {
        return apiKeysCredentialsId;
    }

    public String getPushRepository() {
        return pushRepository;
    }

    public String getPushTag() {
        return pushTag;
    }

    public String getRepository() {
        return repository;
    }

    public boolean getDetectCloudBees() {
        return detectCloudBees;
    }

    public String getTag() {
        return tag;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws AbortException {

        EnvVars envVars;

        try {
            envVars = build.getEnvironment(listener);
        } catch (IOException e) {
            throw new AbortException("Error while retrieving environment variables: " + e.getMessage());
        } catch (InterruptedException e) {
            throw new AbortException("Error while retrieving environment variables: " + e.getMessage());
        }

        List<Builder> builders = ((FreeStyleProject) build.getProject()).getBuilders();
        PrintStream logger = listener.getLogger();

        logger.println(""); // Newline to space out log output.

        ImageReference imageReference = getImageReference(builders, envVars, logger);
        ImageReference pushImageReference = getPushImageReference(imageReference, envVars);
        String imageId = pushImage(imageReference, pushImageReference, build, logger);
        return waitForScanResult(imageId, logger);
    }

    ImageReference getImageReference(List<Builder> builders, EnvVars envVars, PrintStream logger) throws AbortException {

        ImageReference imageReference = null;

        if (detectCloudBees) {

            logger.println("Detecting CloudBees Docker Build and Publish build step (" + CLOUDBEES_BUILDER_FQCN + ")");
            // Search for most recent preceding com.cloudbees.dockerpublish.DockerBuilder builder.
            for (Builder builder: builders) {
                // Stop searching once it reaches this builder itself.
                if (builder == this) {
                    break;
                }

                if (CLOUDBEES_BUILDER_FQCN.equals(builder.getClass().getName())) {

                    try {
                        Method getRepoName = builder.getClass().getMethod(CLOUDBEES_BUILDER_GET_REPO_NAME);
                        Method getRepoTag = builder.getClass().getMethod(CLOUDBEES_BUILDER_GET_REPO_TAG);
                        Method getRegistry = builder.getClass().getMethod(CLOUDBEES_BUILDER_GET_REGISTRY);

                        String repoName = (String) getRepoName.invoke(builder);
                        String repoTag = (String) getRepoTag.invoke(builder);
                        DockerRegistryEndpoint r = (DockerRegistryEndpoint) getRegistry.invoke(builder);

                        imageReference = ImageReference.Create(
                                r.getEffectiveUrl().getHost(),
                                envVars.expand(repoName),
                                envVars.expand(repoTag));

                    } catch (NoSuchMethodException e) {
                        logger.println("Error detecting image from preceding CloudBees Docker Build and Publish build " +
                                "step: " + e.getMessage());
                    } catch (IllegalAccessException e) {
                        logger.println("Error detecting image from preceding CloudBees Docker Build and Publish build " +
                                "step: " + e.getMessage());
                    } catch (InvocationTargetException e) {
                        logger.println("Error detecting image from preceding CloudBees Docker Build and Publish build " +
                                "step: " + e.getMessage());
                    } catch (IOException e) {
                        logger.println("Error detecting registry info from preceding CloudBees Docker Build and " +
                                "Publish build step: " + e.getMessage());
                    }
                }
            }

            if (imageReference == null) {
                throw new AbortException(ERROR_NO_CLOUDBEES);
            } else {
                logger.println("CloudBees Docker Build and Publish build step detected.");
            }

        } else {
            // Image as configured by user, with environment variable support.
            imageReference = ImageReference.Create(
                    null,
                    envVars.expand(getRepository()),
                    envVars.expand(getTag()));
        }

        if (imageReference == null || !imageReference.isValid()) {
            throw new AbortException(ERROR_NO_VALID_IMAGE);
        } else {
            logger.println("Image to be scanned: " + imageReference.getName());
        }

        return imageReference;
    }

    ImageReference getPushImageReference(ImageReference source, EnvVars envVars) {

        envVars = new EnvVars(envVars);

        envVars.put("PUSH_REGISTRY", source.registry == null ? "" : source.registry);
        envVars.put("PUSH_REPOSITORY", source.repository == null ? "" : source.repository);
        envVars.put("PUSH_TAG", source.tag == null ? "" : source.tag);

        return ImageReference.Create(
                TENABLEIO_REGISTRY,
                envVars.expand(getPushRepository()) // Trim leading and trailing "/"s.
                        .replaceAll("^/+", "")
                        .replaceAll("/+$", ""),
                envVars.expand(getPushTag())
        );
    }

    private String pushImage(
            ImageReference srcImageReference,
            ImageReference destImageReference,
            AbstractBuild build,
            PrintStream logger
    )
            throws AbortException {
        ////
        // Push to Tenable.io Container Security service.
        String imageId;
        Process process;
        TenableIoApiKeysCredentials apiKeysCredentials = getApiKeysCredentials();

        try {

            String srcImageName = srcImageReference.getName();
            String destImageName = destImageReference.getName();

            String configPath = build.getWorkspace().getRemote() + "/.tmp_docker_config";

            if (apiKeysCredentials != null) {
                // Login to Tenable.io registry.
                (new ProcessBuilder("docker", "--config", configPath, "login", TENABLEIO_REGISTRY, "-u",
                        apiKeysCredentials.getAccessKey(), "-p",
                        apiKeysCredentials.getSecretKey())).start();
                logger.println("Authenticated with " + TENABLEIO_REGISTRY + ".");
            } else {
                logger.println("No API keys configured.");
            }

            // Tag the image.
            String command = "docker --config " + configPath + " tag " + srcImageName + " " + destImageName;
            logger.println(command);
            Runtime.getRuntime().exec(command).waitFor();

            // Push the image.
            process = (new ProcessBuilder("docker", "--config", configPath, "push", destImageName).redirectErrorStream(true)).start();
            BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = input.readLine()) != null) {
                logger.println(line);
            }
            input.close();

            // Get image ID.
            process = (new ProcessBuilder("docker", "images", "-q", destImageName)).start();
            process.waitFor();
            imageId = (new BufferedReader(new InputStreamReader(process.getInputStream())).readLine());
            logger.println("Image to scan: " + imageId);

            Runtime.getRuntime().exec("rm -r " + configPath).waitFor();

        } catch (IOException e) {
            throw new AbortException("Error executing docker command: " + e.getMessage());
        } catch (InterruptedException e) {
            throw new AbortException("Error executing docker command: " + e.getMessage());
        }

        return imageId;
    }

    private boolean waitForScanResult(String imageId, PrintStream logger) throws AbortException {
        ////
        // Poll for scan result.
        boolean result = false;
        int retries = 0;

        TenableIoApiKeysCredentials apiKeysCredentials = getApiKeysCredentials();

        HttpClient client = new HttpClient();
        HttpMethod method = new GetMethod(String.format(URI_IMAGE_COMPLIANCE_SERVICE, imageId));

        if (apiKeysCredentials != null) {
            method.addRequestHeader("X-ApiKeys",
                    String.format(HEADER_API_KEYS,
                            apiKeysCredentials.getAccessKey(), apiKeysCredentials.getSecretKey()));
        } else {
            logger.println("No API keys configured.");
        }

        logger.println("Waiting scan result...");

        while (true) {
            try {

                if (retries >= MAX_RETRIES) {
                    logger.println("Maximum retry attempts (" + retries + ") reached.");
                    break;
                }

                client.executeMethod(method);
                JSONObject response = JSONObject.fromObject(method.getResponseBodyAsString());

                if (response.has("status")) {
                    String status = response.getString("status");

                    if (status.equals("pass")) {
                        logger.println("Scan status: Passed");
                        result = true;
                        break;
                    } else if (status.equals("fail")) {
                        logger.println("Scan status: Failed");
                        result = false;
                        break;
                    } else if (status.equals("error")) {
                        logger.println("Scan status: " + response.getString("reason"));
                        if (!response.getString("message").equals("report_not_ready")) {
                            retries++;
                        }
                    }
                } else {
                    logger.println(response.toString());
                    retries++;
                }

                try {
                    Thread.sleep(POLL_INTERVAL);
                } catch (InterruptedException e) {
                    logger.println(e.getMessage());
                }
            } catch (IOException e) {
                logger.println(e.getMessage());
                retries++;
            } catch (JSONException e) {
                logger.println(e.getMessage());
                retries++;
            } finally {
                method.releaseConnection();
            }
        }

        return result;
    }

    @CheckForNull
    private TenableIoApiKeysCredentials getApiKeysCredentials() throws AbortException {
        if (StringUtils.isBlank(apiKeysCredentialsId)) {
            return null;
        }
        TenableIoApiKeysCredentials result = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        TenableIoApiKeysCredentials.class, (Item) null, null, null, null),
                CredentialsMatchers.withId(apiKeysCredentialsId)
        );
        if (result == null) {
            throw new AbortException("No credentials found for id \"" + apiKeysCredentialsId + "\"");
        }
        return result;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        static final String DEFAULT_PUSH_REPOSITORY = "${PUSH_REGISTRY}/${PUSH_REPOSITORY}";
        static final String DEFAULT_PUSH_TAG = "${PUSH_TAG}";

        public DescriptorImpl() {
            load();
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        @Override
        public String getDisplayName() {
            return "Docker Image Scan (Tenable.io Container Security)";
        }

        public static String getDefaultPushRepository() {
            return DEFAULT_PUSH_REPOSITORY;
        }

        public static String getDefaultPushTag() {
            return DEFAULT_PUSH_TAG;
        }

        /**
         * Applicable to any kind of project.
         */
        @Override
        public boolean isApplicable(Class type) {
            return FreeStyleProject.class.isAssignableFrom(type);
        }

        @Override
        public boolean configure(StaplerRequest staplerRequest, JSONObject json) throws FormException {
            return true;
        }

        public static ListBoxModel doFillApiKeysCredentialsIdItems() {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withAll(
                            CredentialsProvider.lookupCredentials(
                                    TenableIoApiKeysCredentials.class,
                                    (Item) null,
                                    null,
                                    null,
                                    null
                            )
                    );
        }

    }

}
