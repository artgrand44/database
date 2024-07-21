package org.art.kubernetes;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.art.storage.QueryPounderTest;

public class KQueryPounderTest extends QueryPounderTest {
	public KQueryPounderTest() {
		
		if (System.getProperty("org.art.db.serviceURI") == null) {
			BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
			String uri = null;
			
			while (uri == null || uri.isBlank()) {
				System.out.println("Enter service uri:");

				try {
					uri = reader.readLine();

				} catch (Exception exc) {
					throw new IllegalStateException(exc);
				}
			}
			
			System.setProperty("org.art.db.serviceURI", uri.trim());
		}
		
	}
}
