package com.kezong.fataar

import com.android.build.api.variant.LibraryVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.tasks.TaskDependencyFactory
import org.gradle.api.provider.MapProperty
import org.gradle.internal.model.CalculatedValueContainerFactory

import javax.inject.Inject

class FatAarPlugin implements Plugin<Project> {

    public static final String ARTIFACT_TYPE_AAR = 'aar'

    public static final String ARTIFACT_TYPE_JAR = 'jar'

    private static final String CONFIG_NAME = "embed"

    public static final String CONFIG_SUFFIX = 'Embed'

    private Project project

    private final Collection<Configuration> embedConfigurations = new ArrayList<>()

    private MapProperty<String, List<AndroidArchiveLibrary>> variantPackagesProperty;

    private CalculatedValueContainerFactory calculatedValueContainerFactory

    private TaskDependencyFactory taskDependencyFactory

    private FileResolver fileResolver

    @Inject
    FatAarPlugin(CalculatedValueContainerFactory calculatedValueContainerFactory,
                 TaskDependencyFactory taskDependencyFactory,
                 FileResolver fileResolver) {
        this.calculatedValueContainerFactory = calculatedValueContainerFactory
        this.taskDependencyFactory = taskDependencyFactory
        this.fileResolver = fileResolver
    }

    @Override
    void apply(Project project) {
        this.project = project
        checkAndroidPlugin()
        FatUtils.attach(project)
        project.extensions.create(FatAarExtension.NAME, FatAarExtension)
        createConfigurations()
        registerTransform()
        doAfterEvaluate()
    }

    private registerTransform() {
        variantPackagesProperty = project.objects.mapProperty(String.class, List.class)
        FatAarPluginHelper.registerAsmTransformation(project, variantPackagesProperty)
    }

    private void doAfterEvaluate() {
        embedConfigurations.each {
            if (project.fataar.transitive) {
                it.transitive = true
            }
        }

        project.androidComponents.onVariants(project.androidComponents.selector().all()) { variant ->
            Collection<ResolvedArtifact> artifacts = new ArrayList()
            Collection<ResolvedDependency> firstLevelDependencies = new ArrayList<>()
            project.afterEvaluate {
                Configuration configuration = prepareVariantConfiguration(variant)
                Collection<ResolvedArtifact> resolvedArtifacts = resolveArtifacts(configuration)
                artifacts.addAll(resolveAndroidProjectArtifacts(
                        project,
                        configuration,
                        variant as LibraryVariant,
                        resolvedArtifacts
                ))
                firstLevelDependencies.addAll(configuration.resolvedConfiguration.firstLevelModuleDependencies)

                if (!artifacts.isEmpty()) {
                    def processor = new VariantProcessor(project, variant, variantPackagesProperty)
                    processor.processVariant(artifacts, firstLevelDependencies)
                }
            }
        }
    }

    private Configuration prepareVariantConfiguration(variant) {
        String variantConfigurationName = variant.name + CONFIG_SUFFIX
        Configuration variantConfiguration = project.configurations.findByName(variantConfigurationName)
        Collection<Configuration> matchingConfigurations = embedConfigurations.findAll { configuration ->
            configuration.name == CONFIG_NAME
                    || configuration.name == variant.getBuildType() + CONFIG_SUFFIX
                    || configuration.name == variant.getFlavorName() + CONFIG_SUFFIX
                    || configuration.name == variantConfigurationName
        }

        if (variantConfiguration == null) {
            return matchingConfigurations.first()
        }

        matchingConfigurations.each { sourceConfiguration ->
            if (sourceConfiguration != variantConfiguration) {
                sourceConfiguration.dependencies.each { dependency ->
                    def dependencyCopy = dependency instanceof ProjectDependency ? dependency.copy() : dependency
                    variantConfiguration.dependencies.add(dependencyCopy)
                }
            }
        }
        return variantConfiguration
    }

    private Collection<ResolvedArtifact> resolveAndroidProjectArtifacts(Project ownerProject,
                                                                        Configuration configuration,
                                                                        LibraryVariant variant,
                                                                        Collection<ResolvedArtifact> resolvedArtifacts) {
        Collection<ResolvedArtifact> artifacts = new ArrayList<>(resolvedArtifacts)
        Collection<ResolvedDependency> dependencies = collectDependencies(
                configuration.resolvedConfiguration.firstLevelModuleDependencies
        )
        dependencies.each { dependency ->
            Project dependencyProject = ownerProject.rootProject.allprojects.find { candidate ->
                candidate.name == dependency.moduleName && candidate.group.toString() == dependency.moduleGroup
            }
            if (dependencyProject?.plugins?.hasPlugin('com.android.library')) {
                ResolvedArtifact androidArtifact = FlavorArtifact.createFlavorArtifact(
                        ownerProject,
                        variant,
                        dependency,
                        calculatedValueContainerFactory,
                        fileResolver,
                        taskDependencyFactory
                )
                if (androidArtifact != null) {
                    artifacts.removeAll { artifact ->
                        artifact.moduleVersion.id.group == dependency.moduleGroup &&
                                artifact.moduleVersion.id.name == dependency.moduleName
                    }
                    artifacts.add(androidArtifact)
                }
            }
        }
        return artifacts
    }

    private static Collection<ResolvedDependency> collectDependencies(
            Collection<ResolvedDependency> firstLevelDependencies) {
        Collection<ResolvedDependency> dependencies = new LinkedHashSet<>()
        Collection<ResolvedDependency> pending = new ArrayList<>(firstLevelDependencies)
        while (!pending.isEmpty()) {
            ResolvedDependency dependency = pending.remove(0)
            if (dependencies.add(dependency)) {
                pending.addAll(dependency.children)
            }
        }
        return dependencies
    }

    private void createConfigurations() {
        Configuration embedConf = project.configurations.create(CONFIG_NAME)
        createConfiguration(embedConf)
        FatUtils.logInfo("Creating configuration embed")

        project.android.buildTypes.all { buildType ->
            String configName = buildType.name + CONFIG_SUFFIX
            Configuration configuration = project.configurations.create(configName)
            createConfiguration(configuration)
            FatUtils.logInfo("Creating configuration " + configName)
        }

        project.android.productFlavors.all { flavor ->
            String configName = flavor.name + CONFIG_SUFFIX
            Configuration configuration = project.configurations.create(configName)
            createConfiguration(configuration)
            FatUtils.logInfo("Creating configuration " + configName)
            project.android.buildTypes.all { buildType ->
                String variantName = flavor.name + buildType.name.capitalize()
                String variantConfigName = variantName + CONFIG_SUFFIX
                Configuration variantConfiguration = project.configurations.create(variantConfigName)
                createConfiguration(variantConfiguration)
                FatUtils.logInfo("Creating configuration " + variantConfigName)
            }
        }
    }

    private void checkAndroidPlugin() {
        if (!project.plugins.hasPlugin('com.android.library')) {
            throw new ProjectConfigurationException('fat-aar-plugin must be applied in project that' +
                    ' has android library plugin!', null)
        }
    }

    private void createConfiguration(Configuration embedConf) {
        embedConf.transitive = false
        embedConf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ARTIFACT_TYPE_JAR
        )
        project.gradle.addListener(new EmbedResolutionListener(project, embedConf))
        embedConfigurations.add(embedConf)
    }

    private static Collection<ResolvedArtifact> resolveArtifacts(Configuration configuration) {
        def set = new ArrayList()
        if (configuration != null) {
            configuration.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                if (ARTIFACT_TYPE_AAR == artifact.type || ARTIFACT_TYPE_JAR == artifact.type) {
                    //
                } else {
                    throw new ProjectConfigurationException('Only support embed aar and jar dependencies!', null)
                }
                set.add(artifact)
            }
        }
        return set
    }

    private Collection<ResolvedArtifact> dealUnResolveArtifacts(Configuration configuration,
                                                                LibraryVariant variant,
                                                                Collection<ResolvedArtifact> artifacts) {
        def artifactList = new ArrayList()
        configuration.resolvedConfiguration.firstLevelModuleDependencies.each { dependency ->
            def match = artifacts.any { artifact ->
                dependency.moduleName == artifact.moduleVersion.id.name && dependency.moduleGroup == artifact.moduleVersion.id.group
            }

            if (!match) {
                def flavorArtifact = FlavorArtifact.createFlavorArtifact(
                        project, variant, dependency, calculatedValueContainerFactory, fileResolver, taskDependencyFactory
                )
                if (flavorArtifact != null) {
                    artifactList.add(flavorArtifact)
                }
            }
        }
        return artifactList
    }
}
