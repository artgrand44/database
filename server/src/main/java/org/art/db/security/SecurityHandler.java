package org.art.db.security;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.art.db.common.Key;
import org.art.db.common.Comparison;
import org.art.db.common.DBRuntimeException;
import org.art.db.storage.Files;
import org.art.db.storage.Tree;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SecurityHandler {
	
	private final Tree<String> tree;
	
	private final ConcurrentMap<String, User> users = new ConcurrentHashMap<>();
	
	private boolean enabled = false;
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private static final User NO_SUCH_USER = new User("", "", "", false);
	
	private final static SecurityHandler instance = new SecurityHandler();
	
	private SecurityHandler()  {
		try {
			File file = Files.getSecurity();
			boolean exists = file.exists();
			
			this.tree = new Tree<>(Files.getSecurity(), String.class);
			
			if (!exists) {
				this.createUser("admin", "admin", "admin", true);
			}
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}

	public static SecurityHandler getInstance() {
		return instance;
	}
	
	public void createUser(String name, String userName, String password, boolean admin) throws Exception {
		User oldUser = this.getUser(userName);
		User newUser = new User(name, userName, Utils.encrypt(password), admin);
		
		if (oldUser != NO_SUCH_USER) {
			newUser.setGrants(oldUser.getGrants());
			newUser.setOldPassword(oldUser.getPassword());
			newUser.setOldPasswordsTTL(System.currentTimeMillis() + (20 * 1000));
		}
		
		this.saveUser(newUser);
	}
	

	private User getUser(String userName) throws Exception {
		if (users.containsKey(userName)) {
			return users.get(userName);
		}
		
		String string = this.tree.get(Comparison.EQ, new Key(userName));
		User user = string == null ? NO_SUCH_USER : MAPPER.readValue(string, User.class);
		
		users.put(userName, user);
		
		return user;
		
	}
	
	public void grant(String userName, String table, int role) throws Exception {
		User user = this.getUser(userName);
		
		if (user == NO_SUCH_USER) {
			throw new DBRuntimeException("User " + userName + " does npt exist.");
		}
		
		Integer grants = user.getGrants().get(table);
		
		user.getGrants().put(table, grants == null ? role : User.add(grants, role));
		
		this.saveUser(user);
	}
	
	public void grantAdmin(String userName) throws Exception {
		User user = this.getUser(userName);
		
		if (user == NO_SUCH_USER) {
			throw new DBRuntimeException("User " + userName + " does not exist.");
		}
		
		this.saveUser(new User(user.getName(), user.getUserName(), user.getPassword(), user.getGrants(), true));
	}
	
	public boolean isAdmin(String userName, String password) throws Exception {
		if (!enabled) {
			return true;
		}
		
		User user = this.getUser(userName);
		String decrypted = Utils.decrypt(user.getPassword());
		
		return decrypted.equals(password) && user.isAdmin();
		
	}
	
	public boolean isInRole(String userName, String password, String table, int role) throws Exception {
		
		if (!enabled) {
			return true;
		}
		
		User user = this.getUser(userName);
		String decrypted = Utils.decrypt(user.getPassword());
		Integer grants = user.getGrants().get(table);
		
		if (!decrypted.equals(password)) {
			String old = user.getOldPassword();
			
			if (old == null || System.currentTimeMillis() > user.getOldPasswordsTTL() || !Utils.decrypt(old).equals(password)) {
				return false;
			}
		}
		
		return user.isAdmin() || (grants != null && (grants & role) != 0);
		
	}
	
	public void revoke(String userName, String table, int role) throws Exception {
		User user = this.getUser(userName);
		
		if (user == NO_SUCH_USER) {
			throw new DBRuntimeException("User " + userName + " does npt exist.");
		}
		
		Integer grants = user.getGrants().get(table);
		
		user.getGrants().put(table, grants == null ? role : User.remove(grants, role));
		
		this.saveUser(user);
	}
	
	public void revokeAdmin(String userName) throws Exception {
		User user = this.getUser(userName);
		
		if (user == NO_SUCH_USER) {
			throw new DBRuntimeException("User " + userName + " does not exist.");
		}
	
		this.saveUser(new User(user.getName(), user.getUserName(), user.getPassword(), user.getGrants(), false));
	}
		
		
	private void saveUser(User user) throws Exception {
		this.tree.put(new Key(user.getUserName()), MAPPER.writeValueAsString(user));
		this.tree.flushAll();
		this.users.put(user.getUserName(), user);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
}
