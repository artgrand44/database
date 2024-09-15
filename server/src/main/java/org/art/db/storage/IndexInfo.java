package org.art.db.storage;

import java.util.List;
import java.util.Objects;

public class IndexInfo  {
	String name; 
	boolean unique; 
	boolean built;
	List<String> fields;
	
	public IndexInfo() {
		
	}
	public IndexInfo(String name, boolean unique, List<String> fields) {
		this.name = name;
		this.unique = unique;
		this.fields = fields;
		this.built = false;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public boolean isUnique() {
		return unique;
	}
	public void setUnique(boolean unique) {
		this.unique = unique;
	}
	
	public boolean isBuilt() {
		return built;
	}
	
	public void setBuilt(boolean built) {
		this.built = built;
	}
	
	public List<String> getFields() {
		return fields;
	}
	
	public void setFields(List<String> fields) {
		this.fields = fields;
	}
	@Override
	public int hashCode() {
		return Objects.hash(built, fields, name, unique);
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		IndexInfo other = (IndexInfo) obj;
		return built == other.built && Objects.equals(fields, other.fields) && Objects.equals(name, other.name)
				&& unique == other.unique;
	}
	
	@Override
	public String toString() {
		return "IndexInfo [name=" + name + ", unique=" + unique + ", built=" + built + ", fields=" + fields + "]";
	}
	
	
	
}
