package org.art.db.storage;

import java.util.List;

public record IndexInfo(String name, boolean unique, List<String> fields)  {
		
}
