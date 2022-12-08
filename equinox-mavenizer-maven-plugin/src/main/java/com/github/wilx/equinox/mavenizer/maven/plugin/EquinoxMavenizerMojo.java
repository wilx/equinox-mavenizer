package com.github.wilx.equinox.mavenizer.maven.plugin;

import com.sun.xml.txw2.output.IndentingXMLStreamWriter;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Mojo(name = "equinox-mavenizer", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class EquinoxMavenizerMojo extends AbstractMojo {
    private static final DateTimeFormatter BOM_VERSION_FMT = DateTimeFormatter.ofPattern("uuuuMMdd.HHmmss", Locale.US)
                                                                              .withZone(ZoneId.of("UTC"));
    private static final String XSI_URL = "http://www.w3.org/2001/XMLSchema-instance";
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

    @Parameter(property = "equinox-mavenizer.sdkZipFiles", required = true)
    private List<File> equinoxSdkZipFiles;

    private Path sdkArtifactsDirPath;
    private int artifactCounter = 0;
    private Path bomPath;
    private final String bomVersion = BOM_VERSION_FMT.format(Instant.now());

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        sdkArtifactsDirPath = this.buildDir.toPath().resolve("sdkArtifacts");

        final Map<String, SdkEntry> mappedEntries = new TreeMap<>();
        for (final File equinoxSdkZipFile : this.equinoxSdkZipFiles) {
            try (final ZipFile sdkZipFile = new ZipFile(equinoxSdkZipFile)) {
                final Enumeration<ZipArchiveEntry> entriesInPhysicalOrder = sdkZipFile.getEntriesInPhysicalOrder();
                analyzeSdkArchive(mappedEntries, entriesInPhysicalOrder);

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
            } catch (final IOException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }

        // Analyze metadata for dependencies.
        for (final SdkEntry sdkEntry : mappedEntries.values()) {
            analyzeEntryMetadata(mappedEntries, sdkEntry);
        }

        // Generate POM files with dependencies.
        for (final SdkEntry sdkEntry : mappedEntries.values()) {
            try {
                generatePomFile(mappedEntries, sdkEntry);
            } catch (final IOException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }

        // Generate BOM POM.
        generateBom(mappedEntries.values());
        if (this.bomPath != null) {
            final Artifact bomArtifact = new DefaultArtifact(this.groupId, "bom", "pom", this.bomVersion)
                .setFile(this.bomPath.toFile());
            try {
                final RepositorySystemSession repositorySystemSession = session.getRepositorySession();
                final InstallRequest installRequest = new InstallRequest();
                installRequest.addArtifact(bomArtifact);
                this.repositorySystem.install(repositorySystemSession, installRequest);
            } catch (final InstallationException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }

        // Install extracted JARs and generated POM files together.
        for (final SdkEntry sdkEntry : mappedEntries.values()) {
            installArtifact(sdkEntry);
        }
    }

    private void generateBom(final Collection<SdkEntry> sdkEntries) throws MojoFailureException {
        final String numStr = String.format("%04d", artifactCounter++);
        this.bomPath = sdkArtifactsDirPath.resolve(numStr + "-bom.pom");

        try (final BufferedWriter writer = Files.newBufferedWriter(this.bomPath, StandardCharsets.UTF_8,
            StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            final IndentingXMLStreamWriter xml = newIndentingXMLStreamWriter(writer);

            xmlWritePomPreamble(xml);

            xmlWriteGav(xml, this.groupId, "bom", this.bomVersion);

            xml.writeCharacters("\n");

            xml.writeStartElement("dependencyManagement");

            xml.writeStartElement("dependencies");

            for (final SdkEntry sdkEntry : sdkEntries) {
                xml.writeStartElement("dependency");

                xmlWriteGav(xml, this.groupId, sdkEntry.getArtifactId(), sdkEntry.getVersion());

                xml.writeEndElement(); // dependency
            }

            xml.writeEndElement(); // dependencies

            xml.writeEndElement(); // dependencyManagement

            xml.writeEndElement(); // project

            xml.writeEndDocument();
            xml.flush();
        } catch (final XMLStreamException | IOException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
    }

    private void generatePomFile(final Map<String, SdkEntry> mappedEntries,
        final SdkEntry sdkEntry) throws IOException, MojoFailureException {
        final String artifactId = sdkEntry.getArtifactId();
        final String numStr = String.format("%04d", artifactCounter++);
        final Path pomPath = sdkArtifactsDirPath.resolve(
            numStr + "-" + artifactId + "-" + sdkEntry.getVersion() + ".pom");
        sdkEntry.setPomFile(pomPath);
        try (final BufferedWriter writer = Files.newBufferedWriter(pomPath, StandardCharsets.UTF_8, StandardOpenOption.WRITE,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            final IndentingXMLStreamWriter xml = newIndentingXMLStreamWriter(writer);

            xmlWritePomPreamble(xml);

            xmlWriteGav(xml, this.groupId, sdkEntry.getArtifactId(), sdkEntry.getVersion());

            xml.writeCharacters("\n");

            boolean nlAfterDescOrName = false;
            final String sdkEntryName = sdkEntry.getName();
            if (StringUtils.isNotBlank(sdkEntryName)) {
                writeTag(xml, sdkEntryName, "name");
                nlAfterDescOrName = true;
            }

            final String sdkEntryDesc = sdkEntry.getDescription();
            if (StringUtils.isNotBlank(sdkEntryDesc)) {
                writeTag(xml, sdkEntryDesc, "description");
                nlAfterDescOrName = true;
            }

            if (nlAfterDescOrName) {
                xml.writeCharacters("\n");
            }

            final Collection<String> dependencies = sdkEntry.getDependencies();
            if (dependencies != null && !dependencies.isEmpty()) {
                xml.writeStartElement("dependencies");

                for (final String depArtifactId : dependencies) {
                    final SdkEntry depSdkEntry = mappedEntries.get(depArtifactId);
                    if (depSdkEntry == null) {
                        LOGGER.warn("depSdkEntry == null for {}", depArtifactId);
                        continue;
                    }

                    xml.writeStartElement("dependency");

                    xmlWriteGav(xml, this.groupId, depArtifactId, depSdkEntry.getVersion());

                    xml.writeEndElement(); // dependency
                }

                xml.writeEndElement(); // dependencies
            }

            xml.writeEndElement(); // project

            xml.writeEndDocument();
            xml.flush();
        } catch (final XMLStreamException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
    }

    private static void xmlWritePomPreamble(final IndentingXMLStreamWriter xml) throws XMLStreamException {
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
    }

    @NotNull
    private static IndentingXMLStreamWriter newIndentingXMLStreamWriter(
        @NotNull final BufferedWriter writer) throws XMLStreamException {
        final XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newFactory();
        xmlOutputFactory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, true);
        return new IndentingXMLStreamWriter(xmlOutputFactory.createXMLStreamWriter(writer));
    }

    private static void writeTag(final IndentingXMLStreamWriter xml, final String text, final String tag) throws XMLStreamException {
        xml.writeStartElement(tag);
        xml.writeCharacters(text);
        xml.writeEndElement(); // tag
    }

    private void xmlWriteGav(final IndentingXMLStreamWriter xml, final String depGroupId, final String depArtifactId, final String depVersion)
        throws XMLStreamException {
        writeTag(xml, depGroupId, "groupId");
        writeTag(xml, depArtifactId, "artifactId");
        writeTag(xml, depVersion, "version");
    }

    private void installArtifact(final SdkEntry sdkEntry) throws MojoExecutionException {
        final RepositorySystemSession repositorySystemSession = session.getRepositorySession();
        final InstallRequest installRequest = new InstallRequest();

        final Artifact mainArtifact = new DefaultArtifact(this.groupId, sdkEntry.getArtifactId(), "jar",
            sdkEntry.getVersion()).setFile(sdkEntry.getArtifactPath().toFile());
        installRequest.addArtifact(mainArtifact);

        final Path sourcesPath = sdkEntry.getSourcesPath();
        if (sourcesPath != null) {
            final SubArtifact sourcesArtifact = new SubArtifact(mainArtifact, "source", "jar",
                sourcesPath.toFile());
            installRequest.addArtifact(sourcesArtifact);
        }

        final Path pomPath = sdkEntry.getPomFile();
        if (pomPath != null) {
            final SubArtifact pomArtifact = new SubArtifact(mainArtifact, "", "pom", pomPath.toFile());
            installRequest.addArtifact(pomArtifact);
        }

        try {
            this.repositorySystem.install(repositorySystemSession, installRequest);
        } catch (final InstallationException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private static void copyEntryIntoFile(final ZipFile sdkZipFile, final ZipArchiveEntry artifactEntry,
        final Path artifactPath) throws IOException {
        LOGGER.info("Extracting {} as {}", artifactEntry.getName(), artifactPath);
        try (final InputStream zipEntryInputStream = sdkZipFile.getInputStream(artifactEntry);
             final InputStream inputStream = IOUtils.toBufferedInputStream(zipEntryInputStream, 0x10000);
             final OutputStream outputFileStream = Files.newOutputStream(artifactPath, StandardOpenOption.WRITE,
                 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            IOUtils.copyLarge(inputStream, outputFileStream, new byte[0x10000]);
        }
    }

    private Map<String, SdkEntry> analyzeSdkArchive(final Map<String, SdkEntry> mappedEntries,
        @NotNull final Enumeration<ZipArchiveEntry> entries) {
        entries.asIterator().forEachRemaining(zae -> {
            if (zae.isDirectory() || zae.isUnixSymlink() || !zae.isStreamContiguous()) {
                return;
            }
            analyzeOneEntry(mappedEntries, zae);
        });
        return mappedEntries;
    }

    private static void analyzeOneEntry(final Map<String, SdkEntry> mappedEntries, final ZipArchiveEntry zae) {
        final String fileName = FilenameUtils.getName(zae.getName());
        if (!fileName.endsWith(".jar") || !fileName.contains("_")) {
            return;
        }

        final String baseName = StringUtils.removeEnd(fileName, ".jar");
        final String[] parts = StringUtils.splitPreserveAllTokens(baseName, "_");
        String artifactId;
        if (parts.length > 2) {
            artifactId = StringUtils.join(parts, "_", 0, parts.length - 1);
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

    private static void analyzeEntryMetadata(final Map<String, SdkEntry> mappedEntries,
        final SdkEntry sdkEntry) throws MojoExecutionException, MojoFailureException {
        final Path artifactPath = sdkEntry.getArtifactPath();
        try (final JarFile jarFile = new JarFile(artifactPath.toFile())) {
            final JarEntry manifestJarEntry = jarFile.getJarEntry(JarFile.MANIFEST_NAME);
            if (manifestJarEntry == null) {
                throw new MojoFailureException(sdkEntry.getArtifactId() + " is missing MANIFEST.MF");
            }

            final Map<String, String> manifestMap;
            try (final InputStream manifestInputStream = jarFile.getInputStream(manifestJarEntry)) {
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

            // Try Bundle-Name and Bundle-Description headers first.
            final String manifestBundleName = manifestMap.getOrDefault(Constants.BUNDLE_NAME, "").trim();
            if (StringUtils.isNotBlank(manifestBundleName)
                && !StringUtils.startsWith(manifestBundleName, "%")) {
                sdkEntry.setName(manifestBundleName);
            }

            final String manifestBundleDesc = manifestMap.getOrDefault(Constants.BUNDLE_DESCRIPTION, "").trim();
            if (StringUtils.isNotBlank(manifestBundleDesc)
                && !StringUtils.startsWith(manifestBundleDesc, "%")) {
                sdkEntry.setDescription(manifestBundleDesc);
            }

            final JarEntry bundleProperties = jarFile.getJarEntry("OSGI-INF/l10n/bundle.properties");
            if (bundleProperties != null) {
                try (final InputStream propertiesInput = jarFile.getInputStream(bundleProperties);
                     final Reader reader = new InputStreamReader(propertiesInput, StandardCharsets.UTF_8)) {
                    final Properties props = new Properties(10);
                    props.load(reader);
                    final String bundleName = props.getProperty("bundleName");
                    if (StringUtils.isBlank(sdkEntry.getName())) {
                        sdkEntry.setName(bundleName);
                    }
                }
            }

            final ManifestElement[] fragmentHostElements = ManifestElement.parseHeader(Constants.FRAGMENT_HOST,
                manifestMap.get(Constants.FRAGMENT_HOST));
            if (fragmentHostElements != null) {
                // Record dependency of the host bundle on this fragment.
                final ManifestElement me = fragmentHostElements[0];
                String fragmentHostBSN = me.getValue();
                if (fragmentHostBSN.equals(Constants.SYSTEM_BUNDLE_SYMBOLICNAME)) {
                    fragmentHostBSN = EquinoxContainer.NAME;
                }
                final SdkEntry fragmentHostSdkEntry = mappedEntries.get(fragmentHostBSN);
                fragmentHostSdkEntry.addDependency(symbolicName);

                // Get description and name from fragment.properties.
                final JarEntry fragmentPropertiesEntry = jarFile.getJarEntry("fragment.properties");
                if (fragmentPropertiesEntry != null) {
                    try (final InputStream propertiesInput = jarFile.getInputStream(fragmentPropertiesEntry);
                         final Reader reader = new InputStreamReader(propertiesInput, StandardCharsets.UTF_8)) {
                        final Properties props = new Properties(10);
                        props.load(reader);
                        final String fragmentName = props.getProperty("fragmentName");
                        if (StringUtils.isBlank(sdkEntry.getName())
                            && StringUtils.isNotBlank(fragmentName)) {
                            sdkEntry.setName(fragmentName);
                        }
                        final String providerDesc = props.getProperty("providerDescription");
                        if (StringUtils.isBlank(sdkEntry.getDescription())
                            && StringUtils.isNotBlank(providerDesc)) {
                            sdkEntry.setDescription(providerDesc);
                        }
                    }
                }
            }

            final JarEntry pluginPropertiesEntry = jarFile.getJarEntry("plugin.properties");
            if (pluginPropertiesEntry != null) {
                try (final InputStream propertiesInput = jarFile.getInputStream(pluginPropertiesEntry);
                     final Reader reader = new InputStreamReader(propertiesInput, StandardCharsets.UTF_8)) {
                    final Properties props = new Properties(10);
                    props.load(reader);
                    final String pluginName = props.getProperty("pluginName",
                        props.getProperty("plugin.name",
                            props.getProperty("bundleName",
                                props.getProperty("Bundle-Name",
                                    props.getProperty("Bundle-Name.0")))));
                    if (StringUtils.isBlank(sdkEntry.getName())
                        && StringUtils.isNotBlank(pluginName)) {
                        sdkEntry.setName(pluginName);
                    }
                    final String pluginDescription = props.getProperty("pluginDescription");
                    if (StringUtils.isBlank(sdkEntry.getDescription())
                        && StringUtils.isNotBlank(pluginDescription)) {
                        sdkEntry.setDescription(pluginDescription);
                    }
                }
            }
        } catch (final IOException | BundleException e) {
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
        final Set<String> dependencies = new TreeSet<>();
        String description;
        String name;

        public SdkEntry(final String artifactId, final String version) {
            this.artifactId = artifactId;
            this.version = version;
        }

        public ZipArchiveEntry getArtifactEntry() {
            return artifactEntry;
        }

        public void setArtifactEntry(final ZipArchiveEntry artifactEntry) {
            this.artifactEntry = artifactEntry;
        }

        public ZipArchiveEntry getSourcesEntry() {
            return sourcesEntry;
        }

        public void setSourcesEntry(final ZipArchiveEntry sourcesEntry) {
            this.sourcesEntry = sourcesEntry;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public void setArtifactId(final String artifactId) {
            this.artifactId = artifactId;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(final String version) {
            this.version = version;
        }

        public Path getArtifactPath() {
            return artifactPath;
        }

        public void setArtifactPath(final Path artifactPath) {
            this.artifactPath = artifactPath;
        }

        public Path getSourcesPath() {
            return sourcesPath;
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
            return pomFile;
        }

        public void setPomFile(final Path pomFile) {
            this.pomFile = pomFile;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(final String description) {
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }
}
