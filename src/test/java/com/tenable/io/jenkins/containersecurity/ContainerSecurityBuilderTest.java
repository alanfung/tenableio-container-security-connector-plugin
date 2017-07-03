package com.tenable.io.jenkins.containersecurity;

import com.cloudbees.dockerpublish.DockerBuilder;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.tasks.Builder;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.*;

public class ContainerSecurityBuilderTest {

    @Test
    public void getImageReference_multipleDockerBuilders_correctDockerBuilderDetection() {

        final ContainerSecurityBuilder containerSecurityBuilder = new ContainerSecurityBuilder(
                null, false, null, null, null, null);

        try {
            ImageReference imageReference = containerSecurityBuilder.getImageReference(
                    new ArrayList<Builder>() {{
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(new DockerBuilder("http://host0", "repoName0", "repoTag0"));
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(new DockerBuilder("http://host1", "repoName1", "repoTag1"));
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(containerSecurityBuilder);
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(new DockerBuilder("http://host2", "repoName2", "repoTag2"));
                    }},
                    new EnvVars(),
                    new PrintStream(new ByteArrayOutputStream())
            );

            assertEquals("Correct image registry detection.", "host1", imageReference.registry);
            assertEquals("Correct image repository detection.", "repoName1", imageReference.repository);
            assertEquals("Correct image tag detection.", "repoTag1", imageReference.tag);

        } catch (AbortException e) {
            assertNull("AbortException should not be thrown.", e.getMessage());
        }
    }

    @Test
    public void getImageReference_noPrecedingDockerBuilder_throwAbortException() {

        final ContainerSecurityBuilder containerSecurityBuilder = new ContainerSecurityBuilder(
                null, false, null, null, null, null);

        try {
            containerSecurityBuilder.getImageReference(
                    new ArrayList<Builder>() {{
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(containerSecurityBuilder);
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(new DockerBuilder("http://host2", "repoName2", "repoTag2"));
                    }},
                    new EnvVars(),
                    new PrintStream(new ByteArrayOutputStream())
            );

            assertFalse("AbortException should have been thrown.", true);

        } catch (AbortException e) {
            assertEquals("Appropriate AbortException should be thrown.",
                    ContainerSecurityBuilder.ERROR_NO_CLOUDBEES, e.getMessage());
        }
    }

    @Test
    public void getImageReference_detectCloudBeesFalse_useConfiguredImageReference() {

        final ContainerSecurityBuilder containerSecurityBuilder = new ContainerSecurityBuilder(
                null, true, null, null, "repository", "tag");

        try {
            ImageReference imageReference = containerSecurityBuilder.getImageReference(
                    new ArrayList<Builder>() {{
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(containerSecurityBuilder);
                        add(new ContainerSecurityBuilder(null, false, null, null, null, null));
                        add(new DockerBuilder("http://host2", "repoName", "repoTag"));
                    }},
                    new EnvVars(),
                    new PrintStream(new ByteArrayOutputStream())
            );

            assertEquals("No trailing and leading slashes.", "repository", imageReference.repository);
            assertEquals("No trailing and leading slashes.", "tag", imageReference.tag);

        } catch (AbortException e) {
            assertNull("AbortException should not be thrown.", e.getMessage());
        }
    }

    @Test
    public void getPushImageReference_blankEnvironmentVariables_trimLeadingTrailingSlashes() {

        ContainerSecurityBuilder containerSecurityBuilder = new ContainerSecurityBuilder(
                null, false, ContainerSecurityBuilder.DescriptorImpl.DEFAULT_PUSH_REPOSITORY, null, null, null);

        ImageReference imageReference = containerSecurityBuilder.getPushImageReference(
                ImageReference.Create("someRegistry", "someRepository", null),
                new EnvVars()
        );

        assertEquals("No trailing and leading slashes.", "someRegistry/someRepository", imageReference.repository);

        imageReference = containerSecurityBuilder.getPushImageReference(
                ImageReference.Create(null, "someRepository", null),
                new EnvVars()
        );

        assertEquals("No trailing and leading slashes.", "someRepository", imageReference.repository);

        containerSecurityBuilder = new ContainerSecurityBuilder(
                null, false, "${MY_VARIABLE_0}/${MY_VARIABLE_1}/", null, null, null);

        imageReference = containerSecurityBuilder.getPushImageReference(
                ImageReference.Create("someRegistry", "someRepository", null),
                new EnvVars(new HashMap<String, String>() {{
                    put("MY_VARIABLE_0", "");
                    put("MY_VARIABLE_1", "myValue");
                }})
        );

        assertEquals("No trailing and leading slashes.", "myValue", imageReference.repository);
    }

}
