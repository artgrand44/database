package org.art.storage;

import org.art.db.security.SecurityHandler;
import org.art.db.security.User;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

public class SecurityManagerTest extends BaseTest{

	@Test
	public void test() {
		try {
			SecurityHandler manager = SecurityHandler.getInstance();
			
			manager.setEnabled(true);
			
			manager.createUser("john doe", "jdoe", "password", true);
			
			Assertions.assertThat(manager.isAdmin("jdoe", "password")).isTrue();
			
			manager.revokeAdmin("jdoe");
			Assertions.assertThat(!manager.isAdmin("jdoe", "password")).isTrue();
			
			manager.grantAdmin("jdoe");
			Assertions.assertThat(manager.isAdmin("jdoe", "password")).isTrue();
			
			
			manager.revokeAdmin("jdoe");
			manager.grant("jdoe", "table1", User.SELECT);
			Assertions.assertThat(manager.isInRole("jdoe", "password", "table1", User.SELECT)).isTrue();
			
			manager.revoke("jdoe", "table1", User.SELECT);
			Assertions.assertThat(!manager.isInRole("jdoe", "password", "table1", User.SELECT)).isTrue();
			
			manager.createUser("john doe", "jdoe", "newpassword", true);
			Assertions.assertThat(manager.isInRole("jdoe", "newpassword", "table1", User.SELECT)).isTrue();
			Assertions.assertThat(manager.isInRole("jdoe", "password", "table1", User.SELECT)).isTrue();
			
			Thread.sleep(30 * 1000);
			Assertions.assertThat(!manager.isInRole("jdoe", "password", "table1", User.SELECT)).isTrue();
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
}

