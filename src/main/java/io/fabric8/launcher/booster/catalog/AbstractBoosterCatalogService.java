/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package io.fabric8.launcher.booster.catalog;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import io.fabric8.launcher.booster.CopyFileVisitor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

import io.fabric8.launcher.booster.catalog.spi.BoosterCatalogListener;
import io.fabric8.launcher.booster.catalog.spi.BoosterCatalogPathProvider;
import io.fabric8.launcher.booster.catalog.spi.LocalBoosterCatalogPathProvider;
import io.fabric8.launcher.booster.catalog.spi.NativeGitBoosterCatalogPathProvider;

import javax.annotation.Nullable;

/**
 * This service reads from the Booster catalog Github repository in https://github.com/openshiftio/booster-catalog and
 * marshalls into {@link Booster} objects.
 *
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 * @author <a href="mailto:tschotan@redhat.com">Tako Schotanus</a>
 */
public abstract class AbstractBoosterCatalogService<BOOSTER extends Booster> implements BoosterCatalog<BOOSTER>, BoosterFetcher {
    /**
     * Files to be excluded from project creation
     */
    public static final List<String> EXCLUDED_PROJECT_FILES = Collections
            .unmodifiableList(Arrays.asList(".git", ".travis", ".travis.yml",
                                            ".ds_store",
                                            ".obsidian", ".gitmodules"));

    protected AbstractBoosterCatalogService(AbstractBuilder<BOOSTER, ? extends AbstractBoosterCatalogService<BOOSTER>> config) {
        BoosterCatalogPathProvider provider = config.pathProvider;
        if (provider == null) {
            provider = config.discoverCatalogProvider();
        }
        Objects.requireNonNull(provider, "Booster catalog path provider is required");
        ExecutorService executor = config.executor;
        if (executor == null) {
            executor = ForkJoinPool.commonPool();
        }
        Objects.requireNonNull(executor, "Executor is required");
        logger.info("Using " + provider.getClass().getName());
        this.provider = provider;
        this.indexFilter = config.filter;
        this.listener = config.listener;
        this.transformer = config.transformer;
        this.environment = config.environment;
        this.executor = executor;
    }

    private static final String CLONED_BOOSTERS_DIR = ".boosters";
    
    private static final String COMMON_YAML_FILE = "common.yaml";

    private static final Logger logger = Logger.getLogger(AbstractBoosterCatalogService.class.getName());

    private volatile Set<BOOSTER> boosters = Collections.emptySet();

    private final BoosterCatalogPathProvider provider;

    private final Predicate<BOOSTER> indexFilter;

    @Nullable
    private final BoosterCatalogListener listener;

    @Nullable
    private final BoosterDataTransformer transformer;

    @Nullable
    private final String environment;

    private final ExecutorService executor;

    @Nullable
    private volatile CompletableFuture<Set<BOOSTER>> indexResult;

    @Nullable
    private volatile CompletableFuture<Set<BOOSTER>> prefetchResult;

    /**
     * Indexes the existing YAML files provided by the {@link BoosterCatalogPathProvider} implementation
     */
    public synchronized CompletableFuture<Set<BOOSTER>> index() {
        CompletableFuture<Set<BOOSTER>> ir = indexResult;
        if (ir == null) {
            indexResult = ir = new CompletableFuture<Set<BOOSTER>>();
            final CompletableFuture<Set<BOOSTER>> finalir = ir;
            CompletableFuture.runAsync(() -> {
                try {
                    boosters = new ConcurrentSkipListSet<>(Comparator.comparing(Booster::getId));
                    doIndex();
                    finalir.complete(boosters);
                } catch (Exception ex) {
                    finalir.completeExceptionally(ex);
                }
            }, executor);
        }
        return ir;
    }

    /**
     * Re-runs the indexing of the catalog and the boosters
     * Attention: this won't do anything if indexing is already in progress
     */
    public synchronized CompletableFuture<Set<BOOSTER>> reindex() {
        final CompletableFuture<Set<BOOSTER>> ir = indexResult;
        if (ir != null && ir.isDone()) {
            indexResult = null;
        }
        return index();
    }
    
    /**
     * Pre-fetches the code for {@link Booster}s that were found when running {@link #index}.
     * It's not necessary to run this because {@link Booster} code will be downloaded on
     * demand, but if you want to avoid any delays for the user you can run this method.
     */
    public synchronized CompletableFuture<Set<BOOSTER>> prefetchBoosters() {
        assert(indexResult != null);
        CompletableFuture<Set<BOOSTER>> pr = prefetchResult;
        if (pr == null) {
            prefetchResult = pr = new CompletableFuture<Set<BOOSTER>>();
            final CompletableFuture<Set<BOOSTER>> finalpr = pr;
            CompletableFuture.runAsync(() -> {
                try {
                    logger.info(() -> "Pre-fetching boosters...");
                    for (Booster b : boosters) {
                        try {
                            b.content().get();
                        } catch (InterruptedException e) {
                            break;
                        } catch (Exception e) {
                            // We ignore errors and go on to fetch the next Booster
                            logger.log(Level.SEVERE, "Error while fetching booster '" + b.getName() + "'", e);
                        }
                    }
                    finalpr.complete(boosters);
                    logger.info(() -> "Finished prefetching boosters");
                } catch (Exception ex) {
                    finalpr.completeExceptionally(ex);
                }
            }, executor);
        }
        return pr;
    }

    /**
     * Clones a Booster repo and provides the path where to find it as a result
     */
    @Override
    public CompletableFuture<Path> fetchBoosterContent(Booster booster) {
        synchronized (booster) {
            CompletableFuture<Path> contentResult = new CompletableFuture<>();
            Path contentPath = booster.getContentPath();
            if (contentPath != null && Files.notExists(contentPath)) {
                try {
                    Files.createDirectories(contentPath);
                    CompletableFuture.runAsync(() -> {
                        try {
                            provider.createBoosterContentPath(booster);
                            contentResult.complete(contentPath);
                        } catch (Exception ex) {
                            contentResult.completeExceptionally(ex);
                        }
                    }, executor);
                } catch (IOException ex) {
                    contentResult.completeExceptionally(ex);
                }
            } else {
                contentResult.complete(contentPath);
            }
            return contentResult;
        }
    }

    /**
     * Copies the {@link Booster} contents to the specified {@link Path}
     */
    @Override
    public Path copy(BOOSTER booster, Path projectRoot) throws IOException {
        try {
            Path modulePath = booster.content().get();
            return Files.walkFileTree(modulePath,
                    new CopyFileVisitor(projectRoot,
                                        (p) -> !EXCLUDED_PROJECT_FILES.contains(p.toFile().getName().toLowerCase())));
        } catch (InterruptedException | ExecutionException ex) {
            throw new IOException("Unable to copy Booster", ex);
        }
    }

    // Return all indexed boosters, except the ones that were marked ignored
    // and the ones that don't pass the global `indexFilter`
    protected Stream<BOOSTER> getPrefilteredBoosters() {
        return boosters.stream().filter(indexFilter).filter(ignored(false));
    }

    public static Predicate<Booster> ignored(boolean ignored) {
        return (Booster b) -> b.isIgnore() == ignored;
    }

    @Override
    public Optional<BOOSTER> getBooster(Predicate<BOOSTER> filter) {
        return getPrefilteredBoosters()
                .filter(filter)
                .findAny();
    }

    @Override
    public Collection<BOOSTER> getBoosters() {
        return toBoosters(getPrefilteredBoosters());
    }
    
    @Override
    public Collection<BOOSTER> getBoosters(Predicate<BOOSTER> filter) {
        return toBoosters(getPrefilteredBoosters().filter(filter));
    }

    private Collection<BOOSTER> toBoosters(Stream<BOOSTER> bs) {
        return bs
                .collect(Collectors.toSet());
    }

    private void doIndex() throws Exception {
        try {
            Path catalogPath = provider.createCatalogPath();
            indexBoosters(catalogPath, boosters);
            logger.info(() -> "Finished content indexing");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error while indexing", e);
            throw e;
        }
    }

    protected void indexBoosters(final Path catalogPath, final Set<BOOSTER> boosters) throws IOException {
        indexPath(catalogPath, catalogPath, newBooster(null, this), boosters);
        
        // Notify the listener of all the boosters that were added
        // (this excludes ignored boosters and those filtered by the global indexFilter)
        if (listener != null) {
            getPrefilteredBoosters().forEach(listener::boosterAdded);
        }
    }

    private void indexPath(final Path catalogPath, final Path path, final BOOSTER commonBooster, final Set<BOOSTER> boosters) {
        if (Thread.interrupted()) {
            throw new RuntimeException("Interrupted");
        }
        
        // We skip anything starting with "." and "common.yaml" files
        if (path.startsWith(".")
                || COMMON_YAML_FILE.equals(path.getFileName().toString())) {
            return;
        }
        
        try {
            File file = path.toFile();
            if (file.isDirectory()) {
                // We check if a file named `common.yaml` exists and if so
                // we merge it's data with the `commonBooster` before passing
                // that on to `indexPath()`
                File common = new File(path.toFile(), COMMON_YAML_FILE);
                final BOOSTER activeCommonBooster;
                if (common.isFile()) {
                    BOOSTER localCommonBooster = readBooster(common.toPath());
                    if (localCommonBooster != null) {
                        activeCommonBooster = (BOOSTER) commonBooster.merged(localCommonBooster);
                    } else {
                        activeCommonBooster = commonBooster;
                    }
                } else {
                    activeCommonBooster = commonBooster;
                }
                
                Files.list(path).forEach(subpath -> indexPath(catalogPath, subpath, activeCommonBooster, boosters));
            } else {
                File ioFile = path.toFile();
                String fileName = ioFile.getName().toLowerCase();
                // Skip any file that starts with "."
                if (!fileName.startsWith(".") && (fileName.endsWith(".yaml") || fileName.endsWith(".yml"))) {
                    String id = makeBoosterId(catalogPath, path);
                    Path moduleRoot = catalogPath.resolve(CLONED_BOOSTERS_DIR);
                    Path modulePath = moduleRoot.resolve(id);
                    BOOSTER b = indexBooster(commonBooster, id, catalogPath, path, modulePath);
                    if (b != null) {
                        // Check if we should get a specific environment
                        if (environment != null && !environment.isEmpty()) {
                            String[] envs = environment.split(",");
                            for (String env : envs) {
                                b = (BOOSTER) b.forEnvironment(env);
                            }
                        }
                        boosters.add(b);
                    };
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
    
    // We take the relative path of the booster name, remove the file extension
    // and turn any path symbols into underscores to create a unique booster id.
    // Eg. "http-crud/vertx/booster.yaml" becomes "http-crud_vertx_booster"
    private String makeBoosterId(final Path catalogPath, final Path boosterPath) {
        Path relativePath = catalogPath.relativize(boosterPath);
        String pathString = io.fabric8.launcher.booster.Files.removeFileExtension(relativePath.toString().toLowerCase());
        return pathString.replace('/', '_').replace('\\', '_');
    }
    
    /**
     * Takes a YAML file from the repository and indexes it
     *
     * @param file A YAML file from the booster-catalog repository
     * @return a {@link Booster} or null if the booster could not be read
     */
    @Nullable
    protected BOOSTER indexBooster(BOOSTER common, String id, Path catalogPath, Path file, Path moduleDir) {
        logger.info(() -> "Indexing " + file + " ...");
        BOOSTER booster = readBooster(file);
        if (booster != null) {
            booster = (BOOSTER) common.merged(booster);
            // Booster ID = filename without extension
            booster.setId(id);
            booster.setContentPath(moduleDir);
            
            // We set some useful values in the "metadata" section:
            
            // Information about the booster descriptor, eg if the booster descriptor
            // file was named "http/vertx/community/booster.yaml" the following will
            // be added to the metadata section:
            //    metadata:
            //      descriptor:
            //        name: booster.yaml
            //        path: [http, vertx, community]
            Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
            descriptor.put("name", file.getFileName().toString());
            descriptor.put("path", getDescriptorPathList(file, catalogPath));
            booster.getMetadata().put("descriptor", descriptor);
        }
        return booster;
    }
    
    protected abstract BOOSTER newBooster(@Nullable Map<String, Object> data, BoosterFetcher boosterFetcher);

    @Nullable
    private BOOSTER readBooster(Path file) {
        Representer rep = new Representer();
        rep.getPropertyUtils().setSkipMissingProperties(true);
        Yaml yaml = new Yaml(new YamlConstructor(), rep);
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> boosterData = yaml.loadAs(reader, Map.class);
            if (transformer != null) {
                boosterData = transformer.transform(boosterData);
            }
            return newBooster(boosterData, this);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error while reading " + file, e);
            return null;
        }
    }

    private List<String> getDescriptorPathList(Path boosterPath, Path catalogPath) {
        Path relativePath = catalogPath.relativize(boosterPath);
        Path boosterDir = relativePath.getParent();
        return getPathList(boosterDir);
    }

    private List<String> getPathList(@Nullable Path path) {
        if (path != null) {
            return StreamSupport.stream(path.spliterator(), false)
                    .map(Objects::toString)
                    .collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }
    
    /**
     * {@link BoosterCatalogService} Builder class
     *
     * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
     */
    public abstract static class AbstractBuilder<BOOSTER extends Booster, CATALOG extends AbstractBoosterCatalogService<BOOSTER>> {

        protected String catalogRepositoryURI = LauncherConfiguration.boosterCatalogRepositoryURI();

        protected String catalogRef = "master";

        @Nullable
        protected Path rootDir;

        @Nullable
        protected BoosterCatalogPathProvider pathProvider;

        protected Predicate<BOOSTER> filter = x -> true;

        @Nullable
        protected BoosterCatalogListener listener;

        @Nullable
        protected BoosterDataTransformer transformer;

        @Nullable
        protected String environment;

        @Nullable
        protected ExecutorService executor;

        public AbstractBuilder<BOOSTER, CATALOG> catalogRef(String catalogRef) {
            this.catalogRef = catalogRef;
            return this;
        }

        public AbstractBuilder<BOOSTER, CATALOG> catalogRepository(String catalogRepositoryURI) {
            this.catalogRepositoryURI = catalogRepositoryURI;
            return this;
        }

        public AbstractBuilder<BOOSTER, CATALOG> pathProvider(BoosterCatalogPathProvider pathProvider) {
            this.pathProvider = pathProvider;
            return this;
        }

        public AbstractBuilder<BOOSTER, CATALOG> filter(Predicate<BOOSTER> filter) {
            this.filter = filter;
            return this;
        }

        public AbstractBuilder<BOOSTER, CATALOG> listener(BoosterCatalogListener listener) {
            this.listener = listener;
            return this;
        }

        public AbstractBuilder<BOOSTER, CATALOG> transformer(BoosterDataTransformer transformer) {
            this.transformer = transformer;
            return this;
        }

        public AbstractBuilder<BOOSTER, CATALOG> environment(String environment) {
            this.environment = environment;
            return this;
        }

        public AbstractBuilder<BOOSTER, CATALOG> executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public AbstractBuilder<BOOSTER, CATALOG> rootDir(Path root) {
            this.rootDir = root;
            return this;
        }

        public abstract CATALOG build();

        private BoosterCatalogPathProvider discoverCatalogProvider() {
            final BoosterCatalogPathProvider provider;
            if (LauncherConfiguration.ignoreLocalZip()) {
                provider = new NativeGitBoosterCatalogPathProvider(catalogRepositoryURI, catalogRef, rootDir);
            } else {
                URL resource = getClass().getClassLoader()
                        .getResource(String.format("/booster-catalog-%s.zip", catalogRef));
                if (resource != null) {
                    provider = new LocalBoosterCatalogPathProvider(resource);
                } else {
                    // Resource not found, fallback to original Git resolution
                    provider = new NativeGitBoosterCatalogPathProvider(catalogRepositoryURI, catalogRef, rootDir);
                }
            }
            return provider;
        }
    }
}
