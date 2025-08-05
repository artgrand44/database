package org.art.storage;

import java.io.File;
import java.nio.charset.Charset;

import org.art.db.common.ZipUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.vafer.jdeb.shaded.commons.io.FileUtils;

public class ZipTest extends BaseTest {

	@Test
	public void test() {
		try {
			File root = new File("./test");
			File zips = new File("./zips");
			
			root.mkdirs();
			zips.mkdirs();
			
			FileUtils.cleanDirectory(root);
			FileUtils.cleanDirectory(zips);
			
			for (int top = 0; top < 10; top++) {
				for (int sub = 0; sub < 10; sub++) {
					for (int fileno = 0; fileno < 10; fileno++) {
						String name = String.format("/%s/%s/%s.dat", top, sub, fileno);
						File file = new File(root, name);
						
						file.getParentFile().mkdirs();
						FileUtils.write(file, name, Charset.defaultCharset());
					}
				}
			}
				
			File zip = new File(zips, "test.zip");
			ZipUtils.compressDirectory(root, zip);
			
			FileUtils.cleanDirectory(root);
			ZipUtils.decompressDirectoty(zip, root);
			
			for (int top = 0; top < 10; top++) {
				for (int sub = 0; sub < 10; sub++) {
					for (int fileno = 0; fileno < 10; fileno++) {
						String name = String.format("/%s/%s/%s.dat", top, sub, fileno);
						File file = new File(root, name);
						
						Assertions.assertThat(file.exists()).withFailMessage("File " + file.getCanonicalPath() + " is missing").isTrue();
						Assertions.assertThat(FileUtils.readFileToString(file, Charset.defaultCharset()).equals(name)).withFailMessage("bad content").isTrue();
						
					}
				}
			}
			
			FileUtils.deleteDirectory(root);
			FileUtils.deleteDirectory(zips);
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}
}
