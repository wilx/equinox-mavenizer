package com.github.wilx.equinox.mavenizer.maven.plugin;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
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
    /**
     * Artifact ID of the dependency.
     */
    final Set<String> dependencies = new TreeSet<>();
    String description;
    String name;
    final Set<String> importPackage = new TreeSet<>();
    final Set<String> exportPackage = new TreeSet<>();
    String bsn;
    String fragmentHost;

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

    public Set<String> getImportPackage() {
        return this.importPackage;
    }

    public void addImportPackage(final String ip) {
        this.importPackage.add(ip);
    }

    public Set<String> getExportPackage() {
        return this.exportPackage;
    }

    public void addExportPackage(final String ep) {
        this.exportPackage.add(ep);
    }

    public String getBsn() {
        return this.bsn;
    }

    public void setBsn(final String bsn) {
        this.bsn = bsn;
    }

    public String getFragmentHost() {
        return this.fragmentHost;
    }

    public void setFragmentHost(final String fragmentHost) {
        this.fragmentHost = fragmentHost;
    }
}
