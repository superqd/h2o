
package water.api;

import com.google.gson.JsonObject;
import water.Key;
import water.UKV;
import water.Value;

/**
 *
 * @author peta
 */
public class PutValue extends Request {


  protected final H2OKey _key = new H2OKey(JSON_KEY);
  protected final Str _value = new Str(JSON_VALUE);
  protected final Int _rf = new Int(JSON_RF,2,0,255);

  public PutValue() {
    _requestHelp = "Stores the given value to the cloud under the specified key."
            + " The replication factor may also be specified.";
    _key._requestHelp = "Key under which the value should be stored.";
    _value._requestHelp = "Value that will be stored under the given key.";
    _rf._requestHelp = "Desired replication factor of the key. That is on how"
            + " many nodes should the value be replicated at least";
  }

  @Override public Response serve() {
    JsonObject response = new JsonObject();
    Key k = Key.make(_key.value()._kb, (byte) (int)_rf.value());
    Value v = new Value(k,_value.value().getBytes());
    UKV.put(k,v);
    response.addProperty(JSON_KEY,k.toString());
    response.addProperty(JSON_RF,k.desired());
    response.addProperty(JSON_VALUE_SIZE,v._max);
    return Response.done(response);
  }

}