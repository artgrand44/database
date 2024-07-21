package org.art.storage;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Integration
public class FunctionTest extends BaseIntegrationTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(FunctionTest.class);
	
	
	@Test
	public void test() {
		
			try  {
				startInternal("./application.properties");
				
				Client client = this.getClient().batch(false);
				
				String create = "CREATE TABLE func (id int NOT NULL PRIMARY KEY, str varchar(255), date Date, list LIST, count int)";
				
				String drop = "drop table if exists func";
				String insert = "insert into func (id, str, date, list, count ) values ('1', 'art','2015-04-30', ?, 1)";//yyyy-[m]m-[d]d
				String query1 = "select * from func where id = '1'";
				List<String> list = new ArrayList<>();
				
				client.execute(drop);
				client.execute(create);
				
				list.add("a");
				list.add("b");
				
				client.execute(insert, list);
				
				DocumentSet set = client.executeQuery(query1);
				
				Assertions.assertThat(set.iterator().hasNext());
				Document doc = set.iterator().next();
				
				Assertions.assertThat(Objects.equals(doc.get("str"), "art"));
				
				Object obj = doc.get("date");
				Assertions.assertThat(obj instanceof Date).isTrue();
				
				obj = doc.get("list");
				Assertions.assertThat(obj instanceof List).isTrue();
				
				List<?> alist = (List<?>) obj;
				Assertions.assertThat(alist.get(0).equals("a")).isTrue();
				Assertions.assertThat(alist.get(1).equals("b")).isTrue();
				
				
				set = client.executeQuery("select * from func where id = '1' and str = lower('ART')");				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();				
				
				set = client.executeQuery("select * from func where id = '1' and upper(str) = 'ART'");				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();	
				
				set = client.executeQuery("select * from func where id = '1' and substring(str, 1) = 'rt'");				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();	
				
				set = client.executeQuery("select * from func where id = '1' and substring(str, 0, 1) = 'a'");				
				Assertions.assertThat(set.iterator().hasNext());	
				
				set = client.executeQuery("select * from func where id = '1' and count = min(1, 5)");				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();
				
				set = client.executeQuery("select * from func where id = '1' and count = max(1, 0)");				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();
				
				set = client.executeQuery("select * from func where id = '1' and count = abs(-1)");				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();
				
				set = client.executeQuery("select * from func where id = '1' and year(date) = 2015");				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();
				
				set = client.executeQuery("select upper(str) as UP from func where id = '1'");				
				Assertions.assertThat(set.iterator().hasNext());
				doc = set.iterator().next();
				Assertions.assertThat(doc.get("UP").equals("ART")).isTrue();
				
				
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}
