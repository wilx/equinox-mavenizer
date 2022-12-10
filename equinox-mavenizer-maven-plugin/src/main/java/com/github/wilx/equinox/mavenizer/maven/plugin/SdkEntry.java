package com.github.wilx.equinox.mavenizer.maven.plugin;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

class SdkEntry {
    ZipArchiveEntry artifactEntry;
    ZipArchiveEntry sourcesEntry;
    String artifactId;
    String version;
    Path artifactPath;
    Path sourcesPath;
    Path pomFile;
    final Set<String> dependencies = new TreeSet<>();
    String description;
    String name;

    public SdkEntry(final String artifactId, final String version) {
        this.artifactId = artifactId;
        this.version = version;
    }

    public ZipArchiveEntry getArtifactEntry() {
        return this.artifactEntry;
    }

    public void setArtifactEntry(final ZipArchiveEntry artifactEntry) {
        this.artifactEntry = artifactEntry;
    }

    public ZipArchiveEntry getSourcesEntry() {
        return this.sourcesEntry;
    }

    public void setSourcesEntry(final ZipArchiveEntry sourcesEntry) {
        this.sourcesEntry = sourcesEntry;
    }

    public String getArtifactId() {
        return this.artifactId;
    }

    public void setArtifactId(final String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return this.version;
    }

    public void setVersion(final String version) {
        this.version = version;
    }

    public Path getArtifactPath() {
        return this.artifactPath;
    }

    public void setArtifactPath(final Path artifactPath) {
        this.artifactPath = artifactPath;
    }

    public Path getSourcesPath() {
        return this.sourcesPath;
    }

    public void setSourcesPath(final Path sourcesPath) {
        this.sourcesPath = sourcesPath;
    }

    public void addDependency(final String dep) {
        this.dependencies.add(dep);
    }

    public Collection<String> getDependencies() {
        return this.dependencies;
    }

    public Path getPomFile() {
        return this.pomFile;
    }

    public void setPomFile(final Path pomFile) {
        this.pomFile = pomFile;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public String getName() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }
}
