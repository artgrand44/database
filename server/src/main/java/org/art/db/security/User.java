package org.art.db.security;

import java.util.HashMap;
import java.util.Map;


public class User {
	
	private String name;
	private String userName; 
	private String password;
	private String oldPassword;
	private long oldPasswordsTTL;
	private Map<String, Integer> grants;
	private boolean admin;
	
	public User() {
		
	}
	
	public User(String name, String userName, String password, Map<String, Integer> grants, boolean admin) {
		this.name = name;
		this.userName = userName;
		this.password = password;
		this.grants = grants;
		this.admin = admin;
	}
	
	public User(String name, String userName, String password, boolean admin) {
		this(name, userName, password, new HashMap<>(), admin);
	}
	
	public final static int SELECT = 0x01;
	
	public final static int INSERT = 0x02;
	
	public final static int UPDATE = 0x04;
	
	public final static int DELETE = 0x08;
	
	
	public static int add(int grants, int flag) {
		return grants | flag;
	}
	
	public static int remove (int grants, int flag) {
		return grants & ~flag;
	}

	public String getName() {
		return name;
	}

	public Map<String, Integer> getGrants() {
		return grants;
	}

	public boolean isAdmin() {
		return admin;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setGrants(Map<String, Integer> grants) {
		this.grants = grants;
	}

	public void setAdmin(boolean admin) {
		this.admin = admin;
	}

	public String getUserName() {
		return userName;
	}

	public String getPassword() {
		return password;
	}

	public String getOldPassword() {
		return oldPassword;
	}

	public long getOldPasswordsTTL() {
		return oldPasswordsTTL;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void setOldPassword(String oldPassword) {
		this.oldPassword = oldPassword;
	}

	public void setOldPasswordsTTL(long oldPasswordsTTL) {
		this.oldPasswordsTTL = oldPasswordsTTL;
	}

	@Override
	public String toString() {
		return "User [name=" + name + ", userName=" + userName + ", grants=" + grants + ", admin=" + admin + "]";
	}
	
	
}
