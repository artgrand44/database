package org.art.db.utility;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.art.db.common.DiscoveryHandler;
import org.art.db.server.Server;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

public class UtilTest {
	
	String create = "CREATE TABLE nested (id int NOT NULL PRIMARY KEY, doc Document)";
	String insert = "insert into nested (id, doc ) values ('1', ?)";
	String update = "update nested set doc.hello = 'hi art' where id = '1'";
	String query1 = "select * from nested where id = '1'";
	String query2 = "select * from nested where id = '1' and doc.hello = 'hi art'";
	
	private File dbDir = new File("./dbroot");
	
	
	@BeforeEach
	public void beforeEach() throws Exception {
		this.dbDir.mkdir();
		System.out.println("dbDir " + dbDir.getAbsolutePath());
		
		FileUtils.cleanDirectory(this.dbDir);
	}
	
	@Test
	public void test() {

		try  {
			startInternal("./application.properties");
			File commands = new File("./target/test-classes/commands.txt");
			String[] args = new String[] { "application.properties", "-file", commands.getAbsolutePath(), "-verbose" } ;
			
			System.out.println("file is " + commands.getAbsolutePath());
			Sql.main(args);
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}  finally {
			Server.stop();
		}
	}
	
	public void startInternal(String path) {
		DiscoveryHandler.clear();
		Server.start(path);
		
	}
}
