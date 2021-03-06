/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.xd.module;

import java.io.IOException;
import java.net.URL;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.xd.module.options.ModuleOption;
import org.springframework.xd.module.options.ModuleOptionsMetadata;
import org.springframework.xd.module.options.PojoModuleOptionsMetadata;
import org.springframework.xd.module.options.SimpleModuleOptionsMetadata;

/**
 * Defines a module.
 * 
 * @author Gary Russell
 * 
 */
public class ModuleDefinition {

	private final String name;

	private final ModuleType type;

	private final Resource resource;

	private volatile Properties properties;

	private volatile String definition;

	private final URL[] classpath;

	private ModuleOptionsMetadata moduleOptionsMetadata;

	public ModuleDefinition(String name, ModuleType moduleType) {
		this(name, moduleType, new DescriptiveResource("Dummy resource"));
	}

	public ModuleDefinition(String name, ModuleType type, Resource resource) {
		this(name, type, resource, null);
	}

	public ModuleDefinition(String name, ModuleType type, Resource resource, URL[] classpath) {
		Assert.hasLength(name, "name cannot be blank");
		Assert.notNull(type, "type cannot be null");
		Assert.notNull(resource, "resource cannot be null");
		this.resource = resource;
		this.name = name;
		this.type = type;
		this.classpath = classpath != null && classpath.length > 0 ? classpath : null;
	}

	public String getName() {
		return name;
	}

	public ModuleType getType() {
		return type;
	}

	public Resource getResource() {
		return resource;
	}

	public Properties getProperties() {
		return properties;
	}

	public void setProperties(Properties properties) {
		this.properties = properties;
	}

	public String getDefinition() {
		return definition;
	}

	public void setDefinition(String definition) {
		this.definition = definition;
	}

	public URL[] getClasspath() {
		return classpath;
	}

	@Override
	public String toString() {
		int nbJars = getClasspath() == null ? 0 : getClasspath().length;
		return String.format("%s[%s:%s with %d jars at %s]", getClass().getSimpleName(), getType(), getName(), nbJars,
				getResource().getDescription());
	}

	/**
	 * Return metadata about the options that this module accepts.
	 * 
	 * @return {@code null} if metadata is not available
	 */
	public synchronized ModuleOptionsMetadata getModuleOptionsMetadata() {
		if (moduleOptionsMetadata == null) {
			moduleOptionsMetadata = ModuleOptionsMetadataResolver.create(this);
		}
		return moduleOptionsMetadata;
	}

	/**
	 * Resolves {@link ModuleOptionsMetadata} for a given {@link ModuleDefinition}.
	 * 
	 * The following strategies will be applied in turn:
	 * <ul>
	 * <li>look for a file named {@code <modulename>.properties} next to the module xml definition file</li>
	 * <li>if that file exists
	 * <ul>
	 * <li>look for an {@value ModuleOptionsMetadataResolver#OPTIONS_CLASS} property. If found, use a
	 * {@link PojoModuleOptionsMetadata} backed by that POJO classname</li>
	 * <li>use a {@link SimpleModuleOptionsMetadata} backed by keys of the form {@code options.<name>.description}.
	 * Additionaly, one can provide {@code options.<name>.default} and {@code options.<name>.type} properties.</li>
	 * </ul>
	 * <li>return {@code null}
	 * <ul>
	 * 
	 * @author Eric Bottard
	 */
	private static class ModuleOptionsMetadataResolver {

		private static final Pattern DESCRIPTION_KEY_PATTERN = Pattern.compile("^options\\.([a-zA-Z\\-_0-9]+)\\.description$");

		/**
		 * Name of the property containing a POJO fully qualified classname, which will be used to create a
		 * {@link PojoModuleOptionsMetadata}.
		 */
		private static final String OPTIONS_CLASS = "options_class";

		public static ModuleOptionsMetadata create(ModuleDefinition definition) {
			try {
				Resource propertiesResource = definition.getResource().createRelative(
						definition.getName() + ".properties");
				if (!propertiesResource.exists()) {
					return null;
				}
				else {
					Properties props = new Properties();
					props.load(propertiesResource.getInputStream());
					String pojoClass = props.getProperty(OPTIONS_CLASS);
					if (pojoClass != null) {
						try {
							ClassLoader classLoaderToUse = definition.getClasspath() != null
									? new ParentLastURLClassLoader(definition.getClasspath(),
											ModuleOptionsMetadataResolver.class.getClassLoader())
									: ModuleOptionsMetadataResolver.class.getClassLoader();
							Class<?> clazz = Class.forName(pojoClass, true, classLoaderToUse);
							return new PojoModuleOptionsMetadata(clazz);
						}
						catch (ClassNotFoundException e) {
							throw new IllegalStateException("Unable to load class used by ModuleOptionsMetadata: "
									+ pojoClass, e);
						}
					}
					return makeSimpleModuleOptions(props);
				}
			}
			catch (IOException e) {
				return null;
			}

		}

		private static ModuleOptionsMetadata makeSimpleModuleOptions(Properties props) {
			SimpleModuleOptionsMetadata result = new SimpleModuleOptionsMetadata();
			for (Object key : props.keySet()) {
				if (key instanceof String) {
					String propName = (String) key;
					Matcher matcher = DESCRIPTION_KEY_PATTERN.matcher(propName);
					if (!matcher.matches()) {
						continue;
					}
					String optionName = matcher.group(1);
					String description = props.getProperty(propName);
					Object defaultValue = props.getProperty(String.format("options.%s.default", optionName));
					String type = props.getProperty(String.format("options.%s.type", optionName));
					Class<?> clazz = null;
					if (type != null) {
						try {
							clazz = Class.forName(type);
						}
						catch (ClassNotFoundException e) {
							throw new IllegalStateException("Can't find class used for type of option '" + optionName
									+ "': " + type);
						}
					}
					ModuleOption moduleOption = new ModuleOption(optionName, description).withDefaultValue(
							defaultValue).withType(clazz);
					result.add(moduleOption);
				}
			}
			return result;
		}
	}

}
