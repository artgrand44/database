package org.art.storage;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import org.art.db.storage.BlockFile;
import org.art.db.storage.TreeStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

public class BlockFileTest {

	private static final String BLOCK_0 = "Hello Block 0";
	private static final String BLOCK_1 = "Hello Block 1";
	
	@Test
	public void testBlockFile() {
		try {
			File file = new File("./test/data.dat");
			
			file.mkdirs();
			file.delete();
			
			try (BlockFile block = new BlockFile(file, 1000)) {
				block.writeBlock(BLOCK_0.getBytes(), 0);
				block.writeBlock(BLOCK_1.getBytes(), 1);
			}
			
			try (BlockFile block = new BlockFile(file, 1000)) {
				byte[] bytes0 = block.readBlock(0);
				String string0 = new String(bytes0, 0, BLOCK_0.getBytes().length);
				Assertions.assertThat(string0.equals(BLOCK_0)).isTrue();
				
				
				byte[] bytes1 = block.readBlock(1);
				
				String string1 = new String(bytes1, 0, BLOCK_1.getBytes().length);
				Assertions.assertThat(string1.equals(BLOCK_1)).isTrue();			
			}
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
	
	@Test
	public void testPersistentTreeStore() {
		try {
			File file = new File("./test/tree.dat");
			
			file.mkdirs();
			file.delete();
			
			Assertions.assertThat(!file.equals(file));
			
			Set<Long> freeBlocks = new HashSet<>();
			
			try (TreeStore block = new TreeStore(file, false)) {
				for (int idx = 0; idx < 1000; idx++) {
					long free = block.allocateNode();
					
					Assertions.assertThat(!freeBlocks.contains(free)).isTrue();
					Assertions.assertThat(free >= 1).isTrue().isTrue().isTrue();
					
					freeBlocks.add(free);
				}
			}
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
}
