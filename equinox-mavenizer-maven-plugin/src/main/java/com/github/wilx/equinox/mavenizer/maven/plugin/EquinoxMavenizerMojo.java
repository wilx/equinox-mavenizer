package com.github.wilx.equinox.mavenizer.maven.plugin;

import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeMultimap;
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
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@SuppressWarnings("unused")
@Mojo(name = "equinox-mavenizer", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class EquinoxMavenizerMojo extends AbstractMojo {
    private static final DateTimeFormatter BOM_VERSION_FMT = DateTimeFormatter.ofPattern("uuuuMMdd.HHmmss", Locale.US)
                                                                              .withZone(ZoneId.of("UTC"));
    private static final String XSI_URL = "http://www.w3.org/2001/XMLSchema-instance";
    private static final Logger LOGGER = LoggerFactory.getLogger(EquinoxMavenizerMojo.class);
    public static final ManifestElement[] EMPTY_MANIFEST_ELEMENTS = new ManifestElement[0];

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

    @Parameter
    private Set<String> ignoredBsns;

    private Path sdkArtifactsDirPath;
    private int artifactCounter = 0;
    private Path bomPath;
    private final String bomVersion = BOM_VERSION_FMT.format(Instant.now());

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (this.ignoredBsns == null) {
            this.ignoredBsns = Collections.emptySet();
        }
        this.sdkArtifactsDirPath = this.buildDir.toPath().resolve("sdkArtifacts");

        // Map of artifactIds to SdkEntry records.
        final Map<String, SdkEntry> mappedEntries = new TreeMap<>();
        final Map<String, SdkEntry> bsnMap = new TreeMap<>();
        extractSdkJars(mappedEntries);

        // Analyze metadata for dependencies.
        analyzeMetadata(mappedEntries, bsnMap);

        final SetMultimap<String, String> implementedBy = TreeMultimap.create();
        analyzeDependencies(mappedEntries, bsnMap, implementedBy);

        // Generate POM files with dependencies.
        generatePomFiles(mappedEntries);

        // Generate BOM POM.
        generateBom(mappedEntries.values());
        installBom();

        // Install extracted JARs and generated POM files together.
        installArtifacts(mappedEntries);
    }

    private void installArtifacts(final Map<String, SdkEntry> mappedEntries) throws MojoExecutionException {
        for (final SdkEntry sdkEntry : mappedEntries.values()) {
            installArtifact(sdkEntry);
        }
    }

    private void installBom() throws MojoExecutionException {
        if (this.bomPath != null) {
            final Artifact bomArtifact = new DefaultArtifact(this.groupId, "bom", "pom", this.bomVersion)
                .setFile(this.bomPath.toFile());
            try {
                final RepositorySystemSession repositorySystemSession = this.session.getRepositorySession();
                final InstallRequest installRequest = new InstallRequest();
                installRequest.addArtifact(bomArtifact);
                this.repositorySystem.install(repositorySystemSession, installRequest);
            } catch (final InstallationException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }

    private void generatePomFiles(final Map<String, SdkEntry> mappedEntries) throws MojoFailureException, MojoExecutionException {
        for (final SdkEntry sdkEntry : mappedEntries.values()) {
            try {
                generatePomFile(mappedEntries, sdkEntry);
            } catch (final IOException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }

    private static void analyzeDependencies(final Map<String, SdkEntry> mappedEntries, final Map<String, SdkEntry> bsnMap,
        final SetMultimap<String, String> implementedBy) {
        for (final SdkEntry sdkEntry : mappedEntries.values()) {
            // Add dependency based on fragment host.
            final String fragmentHost = sdkEntry.getFragmentHost();
            if (fragmentHost != null) {
                final SdkEntry hostSdkEntry = bsnMap.get(fragmentHost);
                if (hostSdkEntry != null) {
                    hostSdkEntry.addDependency(hostSdkEntry.getArtifactId());
                }
            }

            // Map exports to providing artifactId.
            final Set<String> exports = sdkEntry.getExportPackage();
            final String artifactId = sdkEntry.getArtifactId();
            for (final String e : exports) {
                implementedBy.put(e, artifactId);
            }
        }

        // Add dependencies based on exported and imported packages.
        for (final SdkEntry sdkEntry : mappedEntries.values()) {
            final Set<String> importPackage = sdkEntry.getImportPackage();
            for (final String pkg : importPackage) {
                final Set<String> artifactIds = implementedBy.get(pkg);
                if (artifactIds != null && !artifactIds.isEmpty()) {
                    artifactIds.forEach(implementorArtifactId -> {
                        if (!implementorArtifactId.equals(sdkEntry.getArtifactId())) {
                            sdkEntry.addDependency(implementorArtifactId);
                        }
                    });
                }
            }
        }
    }

    private void analyzeMetadata(final Map<String, SdkEntry> mappedEntries,
        final Map<String, SdkEntry> bsnMap) throws MojoExecutionException, MojoFailureException {
        final Collection<String> toRemoveArtifactId = new HashSet<>(10);
        for (final Map.Entry<String, SdkEntry> entry : mappedEntries.entrySet()) {
            final SdkEntry sdkEntry = entry.getValue();
            if (!analyzeEntryMetadata(bsnMap, sdkEntry)) {
                toRemoveArtifactId.add(entry.getKey());
            }
        }
        toRemoveArtifactId.forEach(key -> {
            LOGGER.info("Ignoring bundle {}", key);
            mappedEntries.remove(key);
        });
    }

    private void extractSdkJars(final Map<String, SdkEntry> mappedEntries) throws MojoExecutionException {
        for (final File equinoxSdkZipFile : this.equinoxSdkZipFiles) {
            try (final ZipFile sdkZipFile = new ZipFile(equinoxSdkZipFile)) {
                final Enumeration<ZipArchiveEntry> entriesInPhysicalOrder = sdkZipFile.getEntriesInPhysicalOrder();
                analyzeSdkArchive(mappedEntries, entriesInPhysicalOrder);

                // Copy files out of the SDK archive.
                Files.createDirectories(this.sdkArtifactsDirPath);
                for (final Map.Entry<String, SdkEntry> entry : mappedEntries.entrySet()) {
                    final String artifactId = entry.getKey();
                    final SdkEntry sdkEntry = entry.getValue();
                    final ZipArchiveEntry artifactEntry = sdkEntry.getArtifactEntry();
                    if (artifactEntry == null) {
                        LOGGER.warn("{} does not have artifact entry", artifactId);
                        continue;
                    }

                    // Add code JAR.
                    String numStr = String.format("%04d", this.artifactCounter++);
                    final Path artifactPath = this.sdkArtifactsDirPath.resolve(
                        numStr + "-" + artifactId + "-" + sdkEntry.getVersion() + ".jar");
                    sdkEntry.setArtifactPath(artifactPath);
                    copyEntryIntoFile(sdkZipFile, artifactEntry, artifactPath);

                    // Add sources archive, if available.
                    final ZipArchiveEntry sourceEntry = sdkEntry.getSourcesEntry();
                    if (sourceEntry != null) {
                        numStr = String.format("%04d", this.artifactCounter++);
                        final Path sourcesPath = this.sdkArtifactsDirPath.resolve(
                            numStr + "-" + artifactId + "-" + sdkEntry.getVersion() + "-sources.jar");
                        sdkEntry.setSourcesPath(sourcesPath);
                        copyEntryIntoFile(sdkZipFile, sourceEntry, sourcesPath);
                    }
                }
            } catch (final IOException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }

    private void generateBom(final Collection<SdkEntry> sdkEntries) throws MojoFailureException {
        final String numStr = String.format("%04d", this.artifactCounter++);
        this.bomPath = this.sdkArtifactsDirPath.resolve(numStr + "-bom.pom");

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
        final String numStr = String.format("%04d", this.artifactCounter++);
        final Path pomPath = this.sdkArtifactsDirPath.resolve(
            numStr + "-" + artifactId + "-" + sdkEntry.getVersion() + ".pom");
        sdkEntry.setPomFile(pomPath);
        try (final BufferedWriter writer = Files.newBufferedWriter(pomPath, StandardCharsets.UTF_8,
            StandardOpenOption.WRITE,
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

    private static void writeTag(final IndentingXMLStreamWriter xml, final String text,
        final String tag) throws XMLStreamException {
        xml.writeStartElement(tag);
        xml.writeCharacters(text);
        xml.writeEndElement(); // tag
    }

    private void xmlWriteGav(final IndentingXMLStreamWriter xml, final String depGroupId, final String depArtifactId,
        final String depVersion)
        throws XMLStreamException {
        writeTag(xml, depGroupId, "groupId");
        writeTag(xml, depArtifactId, "artifactId");
        writeTag(xml, depVersion, "version");
    }

    private void installArtifact(final SdkEntry sdkEntry) throws MojoExecutionException {
        final RepositorySystemSession repositorySystemSession = this.session.getRepositorySession();
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

    private static final String[] PROP_SOURCES = {"OSGI-INF/l10n/bundle.properties", "fragment.properties", "plugin.properties"};

    private static Properties loadAllPropertiesSources(final JarFile jarFile) throws MojoExecutionException {
        final Properties props = new Properties();
        for (final String source : PROP_SOURCES) {
            final JarEntry jarEntry = jarFile.getJarEntry(source);
            if (jarEntry == null) {
                continue;
            }
            try (final InputStream propertiesInput = jarFile.getInputStream(jarEntry);
                 final Reader reader = new InputStreamReader(propertiesInput, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (final IOException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
        return props;
    }

    private static Optional<String> resolvePlaceholder(final Properties props, final String placeholder) {
        if (placeholder.startsWith("%")) {
            return Optional.ofNullable(props.getProperty(placeholder.substring(1)));
        } else {
            return Optional.of(placeholder);
        }
    }

    private boolean analyzeEntryMetadata(final Map<String, SdkEntry> bsnMap,
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
                return false;
            }
            final ManifestElement[] symbolicNameElements = ManifestElement.parseHeader(Constants.BUNDLE_SYMBOLICNAME,
                symbolicNameStr);
            final String symbolicName = symbolicNameElements[0].getValue();
            if (this.ignoredBsns.contains(symbolicNameStr)) {
                return false;
            }
            sdkEntry.setBsn(symbolicName);
            bsnMap.put(symbolicName, sdkEntry);

            final ManifestElement[] requireBundleElements = parseManifestHeader(manifestMap, Constants.REQUIRE_BUNDLE);
            if (requireBundleElements != null) {
                for (final ManifestElement me : requireBundleElements) {
                    final String value = me.getValue();
                    sdkEntry.addDependency(value);
                }
            }

            final ManifestElement[] importPackages = parseManifestHeader(manifestMap, Constants.IMPORT_PACKAGE);
            for (final ManifestElement pkg : importPackages) {
                sdkEntry.addImportPackage(pkg.getValue());
            }
            final ManifestElement[] dynamicImportPackages = parseManifestHeader(manifestMap,
                Constants.DYNAMICIMPORT_PACKAGE);
            for (final ManifestElement pkg : dynamicImportPackages) {
                final String value = pkg.getValue();
                if (!StringUtils.endsWith(value, "*")) {
                    sdkEntry.addImportPackage(value);
                }
            }

            final ManifestElement[] exportPackages = parseManifestHeader(manifestMap, Constants.EXPORT_PACKAGE);
            for (final ManifestElement pkg : exportPackages) {
                sdkEntry.addExportPackage(pkg.getValue());
            }

            final Properties properties = loadAllPropertiesSources(jarFile);

            final String manifestBundleName = manifestMap.getOrDefault(Constants.BUNDLE_NAME, "").trim();
            if (StringUtils.isNotBlank(manifestBundleName)) {
                resolvePlaceholder(properties, manifestBundleName)
                    .ifPresent(sdkEntry::setName);
            }

            final String manifestBundleDesc = manifestMap.getOrDefault(Constants.BUNDLE_DESCRIPTION, "").trim();
            if (StringUtils.isNotBlank(manifestBundleDesc)) {
                resolvePlaceholder(properties, manifestBundleDesc)
                    .ifPresent(sdkEntry::setDescription);
            }

            final ManifestElement[] fragmentHostElements = parseManifestHeader(manifestMap,
                Constants.FRAGMENT_HOST);
            if (fragmentHostElements.length != 0) {
                // Record dependency of the host bundle on this fragment.
                final ManifestElement me = fragmentHostElements[0];
                String fragmentHostBSN = me.getValue();
                if (fragmentHostBSN.equals(Constants.SYSTEM_BUNDLE_SYMBOLICNAME)) {
                    fragmentHostBSN = EquinoxContainer.NAME;
                }
                sdkEntry.setFragmentHost(fragmentHostBSN);
            }
        } catch (final IOException | BundleException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        return true;
    }

    private static ManifestElement[] parseManifestHeader(@NotNull final Map<String, String> manifestMap,
        @NotNull final String header) throws BundleException {
        final String value = manifestMap.get(header);
        if (value != null) {
            return ManifestElement.parseHeader(header, value);
        } else {
            return EMPTY_MANIFEST_ELEMENTS;
        }
    }
}
