package org.adoptopenjdk.maven.plugins;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import javax.tools.Tool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Goal which:
 * <p>
 * TODO Runs jsplitpkgscan for all artifacts
 */
@Mojo(name = "jsplitpkgscan", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class JsplitpkgscanMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${localRepository}", readonly = true, required = true)
    protected ArtifactRepository localRepository;

    @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
    private File outputDirectory;

    /**
     * Execute jsplitpgkscan tool for the projects artifact against all it's dependencies.
     *
     * @throws MojoExecutionException
     */
    @Override
    public void execute() throws MojoExecutionException {
        ServiceLoader.load(Tool.class).stream()
                .map(toolProvider -> toolProvider.get())
                .filter(tool -> "jsplitpgkscan".equals(tool.toString()))
                .findFirst().ifPresent(this::runJsplitpkgscan);
    }

    void runJsplitpkgscan(Tool tool) {
        Set<String> checkedScopes = Set.of("compile", "runtime");

        //todo: add filter possibility to only include certain scopes
        Predicate<Artifact> filterPredicate = artifact -> checkedScopes.contains(artifact.getScope());

        List<String> artifactJars = new ArrayList<>();
        collectArtifacts(artifact -> artifactJars.add(artifact.getFile().getAbsolutePath()), filterPredicate);

        getLog().debug("Artifacts being processed: " + artifactJars);

        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        tool.run(in, out, err, artifactJars.toArray(new String[0]));

        OutputParser parser = new OutputParser(this::onPackage);
        try {
            parser.parse(out.toByteArray());
        } catch (IOException parseException) {
            getLog().error("Unable to parse tool output", parseException);
        }
    }

    private void onPackage(String packageName, Set<ModuleDetail> moduleDetails) {
        if (moduleDetails.size() > 1) {
            getLog().warn("Split package '" + packageName + "' found: " + moduleDetails);
        }
    }

    private void collectArtifacts(Consumer<Artifact> artifactConsumer, Predicate<Artifact> filterPredicate) {
        // The project's own artifact
        Artifact projectArtifact = project.getArtifact();
        artifactConsumer.accept(projectArtifact);

        // The rest of the project's artifacts
        project.getArtifacts().stream().filter(filterPredicate).forEach(artifactConsumer);

        // the project dependency artifacts
        project.getDependencies().stream()
                .map(dependency -> localRepository.find(createDefaultArtifact(dependency)))
                .filter(filterPredicate)
                .forEach(artifactConsumer);
    }

    private static Artifact createDefaultArtifact(Dependency dependency) {
        return new DefaultArtifact(dependency.getGroupId(),
                dependency.getArtifactId(),
                dependency.getVersion(),
                dependency.getScope(),
                dependency.getType(),
                dependency.getClassifier(),
                new DefaultArtifactHandler(dependency.getType()));
    }
}