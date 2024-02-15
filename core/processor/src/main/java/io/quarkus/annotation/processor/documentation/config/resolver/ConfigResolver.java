package io.quarkus.annotation.processor.documentation.config.resolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.stream.Collectors;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

import io.quarkus.annotation.processor.documentation.config.discovery.DiscoveryConfigGroup;
import io.quarkus.annotation.processor.documentation.config.discovery.DiscoveryConfigProperty;
import io.quarkus.annotation.processor.documentation.config.discovery.DiscoveryConfigRoot;
import io.quarkus.annotation.processor.documentation.config.discovery.DiscoveryRootElement;
import io.quarkus.annotation.processor.documentation.config.discovery.EnumDefinition;
import io.quarkus.annotation.processor.documentation.config.discovery.ResolvedType;
import io.quarkus.annotation.processor.documentation.config.discovery.UnresolvedEnumDefinition;
import io.quarkus.annotation.processor.documentation.config.model.ConfigGroup;
import io.quarkus.annotation.processor.documentation.config.model.ConfigItemCollection;
import io.quarkus.annotation.processor.documentation.config.model.ConfigPhase;
import io.quarkus.annotation.processor.documentation.config.model.ConfigProperty;
import io.quarkus.annotation.processor.documentation.config.model.ConfigRoot;
import io.quarkus.annotation.processor.documentation.config.model.ConfigSection;
import io.quarkus.annotation.processor.documentation.config.model.EnumAcceptedValues;
import io.quarkus.annotation.processor.documentation.config.model.EnumAcceptedValues.EnumAcceptedValue;
import io.quarkus.annotation.processor.documentation.config.model.ResolvedModel;
import io.quarkus.annotation.processor.documentation.config.scanner.ConfigCollector;
import io.quarkus.annotation.processor.documentation.config.util.ConfigNamingUtil;
import io.quarkus.annotation.processor.documentation.config.util.JavadocUtil;
import io.quarkus.annotation.processor.documentation.config.util.Markers;
import io.quarkus.annotation.processor.util.Strings;
import io.quarkus.annotation.processor.util.Utils;

/**
 * The goal of this class is to resolve the elements obtained on scanning/discovery
 * and assemble them into the final model.
 * <p>
 * Note that the model is not exactly final as some elements might not be resolvable
 * because they are inside another module: this annotation processor doesn't cross
 * the module boundaries as it causes a lot of headaches (for instance for the Develocity
 * caching but not only).
 * <p>
 * NEVER CROSS THE STREAMS!
 */
public class ConfigResolver {

    private final Utils utils;
    private final ConfigCollector configCollector;

    public ConfigResolver(Utils utils, ConfigCollector configCollector) {
        this.utils = utils;
        this.configCollector = configCollector;
    }

    public ResolvedModel resolveModel() {
        Map<String, ConfigRoot> configRoots = new HashMap<>();

        boolean fullyResolved = true;

        for (DiscoveryConfigRoot discoveryConfigRoot : configCollector.getConfigRoots()) {
            ConfigRoot configRoot = configRoots.computeIfAbsent(discoveryConfigRoot.getPrefix(),
                    k -> new ConfigRoot(discoveryConfigRoot.getExtension(), discoveryConfigRoot.getPrefix()));

            configRoot.setOverriddenDocFileName(discoveryConfigRoot.getOverriddenDocFileName());
            configRoot.addQualifiedName(discoveryConfigRoot.getQualifiedName());
            configRoot.addUnresolvedInterfaces(discoveryConfigRoot.getUnresolvedInterfaces());

            ResolutionContext context = new ResolutionContext(configRoot.getPrefix(), new ArrayList<>(), discoveryConfigRoot,
                    configRoot,
                    false);
            for (DiscoveryConfigProperty discoveryConfigProperty : discoveryConfigRoot.getProperties().values()) {
                resolveProperty(configRoot, discoveryConfigRoot.getPhase(), context, discoveryConfigProperty);
            }

            configRoots.put(configRoot.getPrefix(), configRoot);

            fullyResolved = fullyResolved && configRoot.isFullyResolved();
        }

        Map<String, ConfigGroup> configGroups = new HashMap<>();

        // TODO GSM: config groups

        return new ResolvedModel(configRoots, configGroups, fullyResolved);
    }

    private void resolveProperty(ConfigRoot configRoot, ConfigPhase phase, ResolutionContext context,
            DiscoveryConfigProperty discoveryConfigProperty) {
        String fullPath = appendPath(context.getPath(), discoveryConfigProperty.getPath());
        List<String> additionalPaths = context.getAdditionalPaths().stream()
                .map(p -> appendPath(p, discoveryConfigProperty.getPath()))
                .collect(Collectors.toCollection(ArrayList::new));

        String typeQualifiedName = discoveryConfigProperty.getType().qualifiedName();

        if (configCollector.isResolvedConfigGroup(typeQualifiedName)) {
            DiscoveryConfigGroup discoveryConfigGroup = configCollector.getResolvedConfigGroup(typeQualifiedName);

            if (discoveryConfigProperty.getType().isMap()) {
                if (discoveryConfigProperty.isUnnamedMapKey()) {
                    ListIterator<String> additionalPathsIterator = additionalPaths.listIterator();

                    additionalPathsIterator.add(fullPath + ConfigNamingUtil.getMapKey(discoveryConfigProperty.getMapKey()));
                    while (additionalPathsIterator.hasNext()) {
                        additionalPathsIterator.add(additionalPathsIterator.next()
                                + ConfigNamingUtil.getMapKey(discoveryConfigProperty.getMapKey()));
                    }
                } else {
                    fullPath += ConfigNamingUtil.getMapKey(discoveryConfigProperty.getMapKey());
                    additionalPaths = additionalPaths.stream()
                            .map(p -> p + ConfigNamingUtil.getMapKey(discoveryConfigProperty.getMapKey()))
                            .collect(Collectors.toCollection(ArrayList::new));
                }
            }

            ResolutionContext configGroupContext;

            if (discoveryConfigProperty.isSection()) {
                ConfigSection configSection = new ConfigSection(typeQualifiedName,
                        discoveryConfigProperty.getSourceName(), fullPath, typeQualifiedName,
                        discoveryConfigProperty.getSection().title(),
                        discoveryConfigProperty.getSection().description());
                context.getItemCollection().addItem(configSection);
                configGroupContext = new ResolutionContext(fullPath, additionalPaths, discoveryConfigGroup, configSection,
                        discoveryConfigProperty.getType().isMap());
            } else {
                configGroupContext = new ResolutionContext(fullPath, additionalPaths, discoveryConfigGroup,
                        context.getItemCollection(), discoveryConfigProperty.getType().isMap());
            }

            for (DiscoveryConfigProperty configGroupProperty : discoveryConfigGroup.getProperties().values()) {
                resolveProperty(configRoot, phase, configGroupContext, configGroupProperty);
            }
        } else if (configCollector.isUnresolvedConfigGroup(typeQualifiedName)) {

        } else {
            String typeBinaryName = discoveryConfigProperty.getType().binaryName();
            String typeSimplifiedName = discoveryConfigProperty.getType().simplifiedName();

            // if the property has a converter, we don't hyphenate the values (per historical rules, not exactly sure of the reason)
            boolean hyphenateEnumValues = !discoveryConfigProperty.isConverted();

            String defaultValue = getDefaultValue(discoveryConfigProperty.getDefaultValue(),
                    discoveryConfigProperty.getDefaultValueForDoc(), discoveryConfigProperty.getType(), hyphenateEnumValues);

            EnumAcceptedValues enumAcceptedValues = null;
            if (discoveryConfigProperty.getType().isEnum()) {
                if (configCollector.isResolvedEnum(typeQualifiedName)) {
                    EnumDefinition enumDefinition = configCollector.getResolvedEnum(typeQualifiedName);
                    Map<String, EnumAcceptedValue> localAcceptedValues = enumDefinition.constants().entrySet().stream()
                            .collect(Collectors.toMap(
                                    e -> e.getValue().hasExplicitValue() ? e.getValue().explicitValue()
                                            : (hyphenateEnumValues ? ConfigNamingUtil.hyphenateEnumValue(e.getKey())
                                                    : e.getKey()),
                                    e -> new EnumAcceptedValue(e.getValue().description(), e.getValue().since())));
                    enumAcceptedValues = new EnumAcceptedValues(enumDefinition.qualifiedName(), localAcceptedValues);
                } else {
                    UnresolvedEnumDefinition unresolvedEnumDefinition = configCollector.getUnresolvedEnum(typeQualifiedName);

                    Map<String, EnumAcceptedValue> localAcceptedValues = unresolvedEnumDefinition.constants().entrySet()
                            .stream()
                            .collect(Collectors.toMap(
                                    e -> e.getValue().hasExplicitValue() ? e.getValue().explicitValue()
                                            : (hyphenateEnumValues ? ConfigNamingUtil.hyphenateEnumValue(e.getKey())
                                                    : e.getKey()),
                                    e -> new EnumAcceptedValue(null, null)));
                    enumAcceptedValues = new EnumAcceptedValues(unresolvedEnumDefinition.qualifiedName(), localAcceptedValues);

                    configRoot.addUnresolvedEnum(discoveryConfigProperty.getType().qualifiedName());
                }
            }

            if (discoveryConfigProperty.getType().isMap()) {
                // it is a leaf pass through map
                typeQualifiedName = discoveryConfigProperty.getType().wrapperType().toString();
                typeSimplifiedName = utils.element().simplifyGenericType(discoveryConfigProperty.getType().wrapperType());

                fullPath += ConfigNamingUtil.getMapKey(discoveryConfigProperty.getMapKey());
                additionalPaths = additionalPaths.stream()
                        .map(p -> p + ConfigNamingUtil.getMapKey(discoveryConfigProperty.getMapKey()))
                        .collect(Collectors.toCollection(ArrayList::new));
            } else if (discoveryConfigProperty.getType().isList()) {
                typeQualifiedName = discoveryConfigProperty.getType().wrapperType().toString();
                typeSimplifiedName = "list of " + typeSimplifiedName;
            }

            // this is a standard property
            ConfigProperty configProperty = new ConfigProperty(phase,
                    context.getDiscoveryRootElement().getQualifiedName(),
                    discoveryConfigProperty.getSourceName(), fullPath, additionalPaths,
                    ConfigNamingUtil.toEnvVarName(fullPath), typeQualifiedName, typeSimplifiedName,
                    discoveryConfigProperty.getType().isMap(), discoveryConfigProperty.getType().isList(),
                    discoveryConfigProperty.getType().isOptional(), discoveryConfigProperty.getMapKey(),
                    discoveryConfigProperty.isUnnamedMapKey(), context.isWithinMap(),
                    discoveryConfigProperty.isConverted(),
                    discoveryConfigProperty.getType().isEnum(),
                    enumAcceptedValues, defaultValue, discoveryConfigProperty.getDescription(),
                    JavadocUtil.getJavadocSiteLink(typeBinaryName),
                    discoveryConfigProperty.isDeprecated(),
                    discoveryConfigProperty.getSince());
            context.getItemCollection().addItem(configProperty);
        }
    }

    public static String getDefaultValue(String defaultValue, String defaultValueForDoc, ResolvedType type,
            boolean hyphenateEnumValues) {
        if (!Strings.isBlank(defaultValueForDoc)) {
            return defaultValueForDoc;
        }

        if (defaultValue == null) {
            return null;
        }

        if (type.isEnum() && hyphenateEnumValues) {
            if (type.isList()) {
                return Arrays.stream(defaultValue.split(Markers.COMMA))
                        .map(v -> ConfigNamingUtil.hyphenateEnumValue(v.trim()))
                        .collect(Collectors.joining(Markers.COMMA));
            } else {
                return ConfigNamingUtil.hyphenateEnumValue(defaultValue.trim());
            }
        }

        return defaultValue;
    }

    public static String getType(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType) {
            DeclaredType declaredType = (DeclaredType) typeMirror;
            TypeElement typeElement = (TypeElement) declaredType.asElement();
            return typeElement.getQualifiedName().toString();
        }
        return typeMirror.toString();
    }

    public static String appendPath(String parentPath, String path) {
        return Markers.PARENT.equals(path) ? parentPath : parentPath + Markers.DOT + path;
    }

    private static class ResolutionContext {

        private final String path;
        private final List<String> additionalPaths;
        private final DiscoveryRootElement discoveryRootElement;
        private final ConfigItemCollection itemCollection;
        private final boolean withinMap;

        private ResolutionContext(String path, List<String> additionalPaths, DiscoveryRootElement discoveryRootElement,
                ConfigItemCollection itemCollection,
                boolean withinMap) {
            this.path = path;
            this.additionalPaths = additionalPaths;
            this.discoveryRootElement = discoveryRootElement;
            this.itemCollection = itemCollection;
            this.withinMap = withinMap;
        }

        public String getPath() {
            return path;
        }

        public List<String> getAdditionalPaths() {
            return additionalPaths;
        }

        public DiscoveryRootElement getDiscoveryRootElement() {
            return discoveryRootElement;
        }

        public ConfigItemCollection getItemCollection() {
            return itemCollection;
        }

        public boolean isWithinMap() {
            return withinMap;
        }
    }
}
