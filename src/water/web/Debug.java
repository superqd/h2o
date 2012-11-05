
package water.web;

import com.google.gson.JsonObject;
import java.util.Properties;
import water.DKV;
import water.Key;
import water.Value;
import water.parser.FastParser;
import water.parser.ParseDataset;

/**
 *
 * @author peta
 */
public class Debug extends JSONPage {

  @Override public String[] requiredArguments() {
    return new String[] { "action" };
  }
  
  @Override public JsonObject serverJson(Server server, Properties args, String sessionID) throws PageError {
    JsonObject result = new JsonObject();
    String action = args.getProperty("action");
    try {
      // 
      if (action.equals("freeMem")) {
        Value v = DKV.get(Key.make(args.getProperty("key","__NOKEY__")));
        if (v == null)
          throw new Exception("Given key not found");
        if (!v.is_persisted())
          throw new Exception("Value is not persistent. Cannot be freed mem");
        v.free_mem();
        
      } else if (action.equals("fastParse")) {
        ParseDataset p = new ParseDataset();
        FastParser parser = new FastParser(null, 4, (byte) ',', (byte) '.', null);
        parser.parse(Key.make(args.getProperty("key")),false);
      } else {
        throw new Exception("Action "+action+" not recognized by the debug interface.");
      }
    } catch (Exception e) {
      result.addProperty("Error",e.getMessage());
    }
    return result;
  }
  
  
}