package org.art.db.ui;

public class Setting implements Comparable<Setting>{
	private String property;
	private String name;
	private String pattern;
	private String value;
	private String error;
	
	public Setting() {
		
	}
	
	public Setting(String property, String name, String pattern, String error, String value) {
		this.property = property;
		this.name = name;
		this.pattern = pattern;
		this.error = error;
		this.value = value;
	}

	

	@Override
	public int compareTo(Setting that) {
		return this.name.compareTo(that.name);
	}

	public String getProperty() {
		return property;
	}

	public void setProperty(String property) {
		this.property = property;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	
}
