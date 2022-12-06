package com.github.wilx.equinox.mavenizer.maven.plugin;

import com.sun.xml.txw2.output.IndentingXMLStreamWriter;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.installation.InstallRequest;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.util.artifact.SubArtifact;
import org.eclipse.osgi.framework.util.CaseInsensitiveDictionaryMap;
import org.eclipse.osgi.internal.framework.EquinoxContainer;
import org.eclipse.osgi.util.ManifestElement;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Mojo(name = "equinox-mavenizer", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class EquinoxMavenizerMojo extends AbstractMojo {
    public static final String XSI_URL = "http://www.w3.org/2001/XMLSchema-instance";
    private static final Logger LOGGER = LoggerFactory.getLogger(EquinoxMavenizerMojo.class);

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project.build.directory}")
    private File buildDir;

    @Parameter(property = "equinox-mavenizer.groupId", required = true)
    private String groupId;

    @Parameter(property = "equinox-mavenizer.sdkZipFile", required = true)
    private File equinoxSdkZipFile;

    private Path sdkArtifactsDirPath;

    private int artifactCounter = 0;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        sdkArtifactsDirPath = this.buildDir.toPath().resolve("sdkArtifacts");

        try (final ZipFile sdkZipFile = new ZipFile(this.equinoxSdkZipFile)) {
            final Enumeration<ZipArchiveEntry> entriesInPhysicalOrder = sdkZipFile.getEntriesInPhysicalOrder();
            final Map<String, SdkEntry> mappedEntries = analyzeSdkArchive(entriesInPhysicalOrder);

            // Copy files out of the SDK archive.
            Files.createDirectories(sdkArtifactsDirPath);
            for (final Map.Entry<String, SdkEntry> entry : mappedEntries.entrySet()) {
                final String artifactId = entry.getKey();
                final SdkEntry sdkEntry = entry.getValue();
                final ZipArchiveEntry artifactEntry = sdkEntry.getArtifactEntry();
                if (artifactEntry == null) {
                    LOGGER.warn("{} does not have artifact entry", artifactId);
                    continue;
                }

                String numStr = String.format("%04d", artifactCounter++);
                final Path artifactPath = sdkArtifactsDirPath.resolve(
                    numStr + "-" + artifactId + "-" + sdkEntry.getVersion() + ".jar");
                sdkEntry.setArtifactPath(artifactPath);
                copyEntryIntoFile(sdkZipFile, artifactEntry, artifactPath);

                final ZipArchiveEntry sourceEntry = sdkEntry.getSourcesEntry();
                if (sourceEntry != null) {
                    numStr = String.format("%04d", artifactCounter++);
                    final Path sourcesPath = sdkArtifactsDirPath.resolve(
                        numStr + "-" + artifactId + "-" + sdkEntry.getVersion() + "-sources.jar");
                    sdkEntry.setSourcesPath(sourcesPath);
                    copyEntryIntoFile(sdkZipFile, sourceEntry, sourcesPath);
                }
            }

            // Analyze metadata for dependencies.
            for (final SdkEntry sdkEntry : mappedEntries.values()) {
                analyzeEntryMetadata(mappedEntries, sdkEntry);
            }

            // Generate POM files with dependencies.
            for (final SdkEntry sdkEntry : mappedEntries.values()) {
                generatePomFile(mappedEntries, sdkEntry);
            }

            // Install extracted JARs and generated POM files together.
            for (final SdkEntry sdkEntry : mappedEntries.values()) {
                installArtifact(sdkEntry);
            }
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void generatePomFile(Map<String, SdkEntry> mappedEntries,
        final SdkEntry sdkEntry) throws IOException, MojoFailureException {
        final String artifactId = sdkEntry.getArtifactId();
        String numStr = String.format("%04d", artifactCounter++);
        final Path pomPath = sdkArtifactsDirPath.resolve(
            numStr + "-" + artifactId + "-" + sdkEntry.getVersion() + ".pom");
        try (BufferedWriter writer = Files.newBufferedWriter(pomPath, StandardCharsets.UTF_8, StandardOpenOption.WRITE,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newFactory();
            xmlOutputFactory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, true);
            final IndentingXMLStreamWriter xml = new IndentingXMLStreamWriter(
                xmlOutputFactory.createXMLStreamWriter(writer));

            xml.writeStartDocument("UTF-8", "1.0");


            final String mavenUri = "http://maven.apache.org/POM/4.0.0";
            xml.setDefaultNamespace(mavenUri);
            xml.setPrefix("xsi", XSI_URL);

            xml.writeStartElement("project");
            xml.writeAttribute(XSI_URL, "schemaLocation",
                "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd");

            xml.writeStartElement(mavenUri, "modelVersion");
            xml.writeCharacters("4.0.0");
            xml.writeEndElement();

            xml.writeCharacters("\n");

            xml.writeStartElement("groupId");
            xml.writeCharacters(this.groupId);
            xml.writeEndElement(); // groupId

            xml.writeStartElement("artifactId");
            xml.writeCharacters(sdkEntry.getArtifactId());
            xml.writeEndElement(); // artifactId

            xml.writeStartElement("version");
            xml.writeCharacters(sdkEntry.getVersion());
            xml.writeEndElement(); // version

            xml.writeCharacters("\n");

            final Collection<String> dependencies = sdkEntry.getDependencies();
            if (dependencies != null && !dependencies.isEmpty()) {
                LOGGER.info("{} has dependencies: {}", artifactId, dependencies);
                xml.writeStartElement("dependencies");

                for (final String depArtifactId : dependencies) {
                    final SdkEntry depSdkEntry = mappedEntries.get(depArtifactId);
                    if (depSdkEntry == null) {
                        LOGGER.warn("depSdkEntry == null for {}", depArtifactId);
                        continue;
                    }

                    xml.writeStartElement("dependency");

                    xml.writeStartElement("groupId");
                    xml.writeCharacters(this.groupId);
                    xml.writeEndElement(); // groupId

                    xml.writeStartElement("artifactId");
                    xml.writeCharacters(depArtifactId);
                    xml.writeEndElement(); // artifactId

                    xml.writeStartElement("version");
                    xml.writeCharacters(depSdkEntry.getVersion());
                    xml.writeEndElement(); // version

                    xml.writeEndElement(); // dependency
                }

                xml.writeEndElement(); // dependencies
            }

            xml.writeEndElement(); // project

            xml.writeEndDocument();
            xml.flush();
        } catch (XMLStreamException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
    }

    private void installArtifact(final SdkEntry sdkEntry) throws MojoExecutionException {
        RepositorySystemSession repositorySystemSession = session.getRepositorySession();
        InstallRequest installRequest = new InstallRequest();

        final Artifact mainArtifact = new DefaultArtifact(this.groupId, sdkEntry.getArtifactId(), "jar",
            sdkEntry.getVersion()).setFile(sdkEntry.getArtifactPath().toFile());
        installRequest.addArtifact(mainArtifact);

        if (sdkEntry.getSourcesPath() != null) {
            final SubArtifact sourcesArtifact = new SubArtifact(mainArtifact, "source", "jar",
                sdkEntry.getSourcesPath().toFile());
            installRequest.addArtifact(sourcesArtifact);
        }

        try {
            this.repositorySystem.install(repositorySystemSession, installRequest);
        } catch (final InstallationException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private static void copyEntryIntoFile(ZipFile sdkZipFile, ZipArchiveEntry artifactEntry,
        Path artifactPath) throws IOException {
        LOGGER.info("Extracting {} as {}", artifactEntry.getName(), artifactPath);
        try (final InputStream zipEntryInputStream = CloseShieldInputStream.wrap(
            sdkZipFile.getInputStream(artifactEntry));
             final InputStream inputStream = IOUtils.toBufferedInputStream(zipEntryInputStream, 0x10000);
             final OutputStream outputFileStream = Files.newOutputStream(artifactPath, StandardOpenOption.WRITE,
                 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            IOUtils.copyLarge(inputStream, outputFileStream, new byte[0x10000]);
        }
    }

    private Map<String, SdkEntry> analyzeSdkArchive(@NotNull Enumeration<ZipArchiveEntry> entries) {
        final Map<String, SdkEntry> mappedEntries = new TreeMap<>();
        entries.asIterator().forEachRemaining(zae -> {
            if (zae.isDirectory() || zae.isUnixSymlink() || !zae.isStreamContiguous()) {
                return;
            }
            analyzeOneEntry(mappedEntries, zae);
        });
        return mappedEntries;
    }

    private static void analyzeOneEntry(Map<String, SdkEntry> mappedEntries, ZipArchiveEntry zae) {
        String fileName = FilenameUtils.getName(zae.getName());
        if (!fileName.endsWith(".jar") || !fileName.contains("_")) {
            return;
        }

        final String baseName = StringUtils.removeEnd(fileName, ".jar");
        String[] parts = StringUtils.splitPreserveAllTokens(baseName, "_");
        String artifactId;
        LOGGER.info("parts: {}", parts);
        if (parts.length > 2) {
            artifactId = StringUtils.join(parts, "_", 0, parts.length - 1);
            LOGGER.info("artifactId from more than two components: {}", artifactId);
        } else {
            artifactId = parts[0];
        }
        final String version = parts[parts.length - 1];
        final SdkEntry sdkEntry;
        if (artifactId.endsWith(".source")) {
            artifactId = StringUtils.removeEnd(artifactId, ".source");
            final String aid = artifactId;
            sdkEntry = mappedEntries.computeIfAbsent(artifactId, k -> new SdkEntry(aid, version));
            sdkEntry.setSourcesEntry(zae);
        } else {
            final String aid = artifactId;
            sdkEntry = mappedEntries.computeIfAbsent(artifactId, k -> new SdkEntry(aid, version));
            sdkEntry.setArtifactEntry(zae);
        }
        sdkEntry.setVersion(version);
    }

    private static void analyzeEntryMetadata(Map<String, SdkEntry> mappedEntries,
        SdkEntry sdkEntry) throws MojoExecutionException, MojoFailureException {
        final Path artifactPath = sdkEntry.getArtifactPath();
        try (final JarFile jarFile = new JarFile(artifactPath.toFile())) {
            final JarEntry manifestJarEntry = jarFile.getJarEntry(JarFile.MANIFEST_NAME);
            if (manifestJarEntry == null) {
                throw new MojoFailureException(sdkEntry.getArtifactId() + " is missing MANIFEST.MF");
            }

            final Map<String, String> manifestMap;
            try (final InputStream manifestInputStream = CloseShieldInputStream.wrap(
                jarFile.getInputStream(manifestJarEntry))) {
                manifestMap = ManifestElement.parseBundleManifest(manifestInputStream,
                    new CaseInsensitiveDictionaryMap<>(10));
            }
            final String symbolicNameStr = manifestMap.get(Constants.BUNDLE_SYMBOLICNAME);
            if (symbolicNameStr == null) {
                return;
            }
            final ManifestElement[] symbolicNameElements = ManifestElement.parseHeader(Constants.BUNDLE_SYMBOLICNAME,
                symbolicNameStr);
            final String symbolicName = symbolicNameElements[0].getValue();

            final ManifestElement[] requireBundleElements = ManifestElement.parseHeader(Constants.REQUIRE_BUNDLE,
                manifestMap.get(Constants.REQUIRE_BUNDLE));
            if (requireBundleElements != null) {
                for (final ManifestElement me : requireBundleElements) {
                    final String value = me.getValue();
                    sdkEntry.addDependency(value);
                }
            }

            final ManifestElement[] fragmentHostElements = ManifestElement.parseHeader(Constants.FRAGMENT_HOST,
                manifestMap.get(Constants.FRAGMENT_HOST));
            if (fragmentHostElements != null) {
                final ManifestElement me = fragmentHostElements[0];
                String fragmentHostBSN = me.getValue();
                if (fragmentHostBSN.equals(Constants.SYSTEM_BUNDLE_SYMBOLICNAME)) {
                    fragmentHostBSN = EquinoxContainer.NAME;
                }
                final SdkEntry fragmentHostSdkEntry = mappedEntries.get(fragmentHostBSN);
                fragmentHostSdkEntry.addDependency(symbolicName);
            }
        } catch (IOException | BundleException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    static class SdkEntry {
        ZipArchiveEntry artifactEntry;
        ZipArchiveEntry sourcesEntry;
        String artifactId;
        String version;
        Path artifactPath;
        Path sourcesPath;
        Path pomFile;
        Set<String> dependencies = new TreeSet<>();

        public SdkEntry(String artifactId, String version) {
            this.artifactId = artifactId;
            this.version = version;
        }

        public ZipArchiveEntry getArtifactEntry() {
            return artifactEntry;
        }

        public void setArtifactEntry(ZipArchiveEntry artifactEntry) {
            this.artifactEntry = artifactEntry;
        }

        public ZipArchiveEntry getSourcesEntry() {
            return sourcesEntry;
        }

        public void setSourcesEntry(ZipArchiveEntry sourcesEntry) {
            this.sourcesEntry = sourcesEntry;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public void setArtifactId(String artifactId) {
            this.artifactId = artifactId;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public Path getArtifactPath() {
            return artifactPath;
        }

        public void setArtifactPath(Path artifactPath) {
            this.artifactPath = artifactPath;
        }

        public Path getSourcesPath() {
            return sourcesPath;
        }

        public void setSourcesPath(Path sourcesPath) {
            this.sourcesPath = sourcesPath;
        }

        public void addDependency(String dep) {
            this.dependencies.add(dep);
        }

        public Collection<String> getDependencies() {
            return this.dependencies;
        }

        public Path getPomFile() {
            return pomFile;
        }

        public void setPomFile(Path pomFile) {
            this.pomFile = pomFile;
        }
    }
}
