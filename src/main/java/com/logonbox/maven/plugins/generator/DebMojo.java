package com.logonbox.maven.plugins.generator;

import java.util.Map;
import java.util.Properties;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.vafer.jdeb.utils.MapVariableResolver;
import org.vafer.jdeb.utils.VariableResolver;

@Mojo(name = "jdeb", defaultPhase = LifecyclePhase.PACKAGE)
public class DebMojo extends org.vafer.jdeb.maven.DebMojo {

	@Parameter
	protected Properties properties;

	@Override
	protected VariableResolver initializeVariableResolver(Map<String, String> variables) {
		MapVariableResolver mvr = (MapVariableResolver) super.initializeVariableResolver(variables);
		return new VariableResolver() {
			@Override
			public String get(String pKey) {
				return properties != null && properties.containsKey(pKey) ? properties.getProperty(pKey)
						: mvr.get(pKey);
			}
		};
	}

}
