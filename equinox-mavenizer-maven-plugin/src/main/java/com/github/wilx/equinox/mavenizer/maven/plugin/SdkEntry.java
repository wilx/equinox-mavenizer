package com.github.wilx.equinox.mavenizer.maven.plugin;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;

class SdkEntry {
    ZipArchiveEntry artifactEntry;
    ZipArchiveEntry sourcesEntry;
    String artifactId;
    String version;
    Path artifactPath;
    Path sourcesPath;
    Path pomFile;
    boolean isDSImpl;
    boolean requiresDS;
    boolean isServiceLoaderImpl;
    boolean requiresServiceLoader;
    boolean hasBundleActivator;

    /**
     * Artifact ID of the dependency.
     */
    final Set<Dependency> dependencies = new TreeSet<>(
            Comparator.comparing(Dependency::artifactId)
                    .thenComparing(Dependency::dependencyType));
    String description;
    String name;
    final Set<ImportPackage> importPackage = new TreeSet<>(
            Comparator.comparing(ImportPackage::pkg)
                    .thenComparing(ImportPackage::dependencyType));
    final Set<RequireBundle> requireBundle = new TreeSet<>(
            Comparator.comparing(RequireBundle::bundle)
                    .thenComparing(RequireBundle::dependencyType));
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

    public void addDependency(final String artifactId, final DependencyType dependencyType) {
        if (artifactId.equals(this.getArtifactId())) {
            throw new RuntimeException("Self reference in dependencies: " + artifactId);
        }
        if (this.artifactId.equals("org.eclipse.osgi")) {
            // Don't add dependencies to the core OSGi bundle.
            return;
        }

        if (dependencyType == DependencyType.OPTIONAL) {
            if (!this.dependencies.contains(new Dependency(artifactId, DependencyType.NORMAL))) {
                this.dependencies.add(new Dependency(artifactId, dependencyType));
            }
        } else {
            this.dependencies.remove(new Dependency(artifactId, DependencyType.OPTIONAL));
            this.dependencies.add(new Dependency(artifactId, dependencyType));
        }
    }

    public Collection<Dependency> getDependencies() {
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

    public Set<ImportPackage> getImportPackage() {
        return this.importPackage;
    }

    public void addImportPackage(final String ip, final DependencyType type) {
        this.importPackage.add(new ImportPackage(ip, type));
    }

    public Set<RequireBundle> getRequireBundle() {
        return this.requireBundle;
    }

    public void addRequireBundle(final String bundle, final DependencyType type) {
        this.requireBundle.add(new RequireBundle(bundle, type));
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

    public boolean isDSImpl() {
        return this.isDSImpl;
    }

    public void setDSImpl(final boolean DSImpl) {
        this.isDSImpl = DSImpl;
    }

    public boolean isRequiresDS() {
        return this.requiresDS;
    }

    public void setRequiresDS(final boolean requiresDS) {
        this.requiresDS = requiresDS;
    }

    public boolean isServiceLoaderImpl() {
        return this.isServiceLoaderImpl;
    }

    public void setServiceLoaderImpl(final boolean serviceLoaderImpl) {
        this.isServiceLoaderImpl = serviceLoaderImpl;
    }

    public boolean isRequiresServiceLoader() {
        return this.requiresServiceLoader;
    }

    public void setRequiresServiceLoader(boolean requiresServiceLoader) {
        this.requiresServiceLoader = requiresServiceLoader;
    }

    public boolean hasBundleActivator() {
        return this.hasBundleActivator;
    }

    public void setHasBundleActivator(final boolean hasBundleActivator) {
        this.hasBundleActivator = hasBundleActivator;
    }

    public boolean isRequiresStart() {
        return this.isDSImpl || this.requiresDS || this.hasBundleActivator || this.isServiceLoaderImpl || this.requiresServiceLoader;
    }

    public enum DependencyType {
        NORMAL,
        OPTIONAL
    }

    public record Dependency (String artifactId, DependencyType dependencyType)
    { }

    public record ImportPackage (String pkg, DependencyType dependencyType)
    { }

    public record RequireBundle (String bundle, DependencyType dependencyType)
    { }
}
